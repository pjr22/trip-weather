package com.pjr22.tripweather.scheduler;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.RouteRepository;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import com.pjr22.tripweather.service.UserManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Scheduled cleanup of data that grows without bound otherwise.
 *
 * <p>Two responsibilities, both fired by the same cron expression
 * ({@code route.cleanup.cron}, default daily at 03:00) and now both
 * recorded in {@code loader_runs} (Phase 2 of ADMIN_CONSOLE.md):
 * <ol>
 *   <li><b>Two-stage route purge</b> (loader name {@code guest-route-cleanup}):
 *       <ul>
 *         <li>Stage 1 — soft-delete guest-owned routes whose {@code created}
 *             is older than {@code route.cleanup.guest-route-retention-days}
 *             (default 30) and that aren't already marked deleted.
 *             <b>Gated</b> by {@code route.cleanup.enabled} so operators who
 *             want to keep guest data around can flip it off without
 *             disabling the rest of the job.</li>
 *         <li>Stage 2 — hard-delete <em>any</em> route (guest or otherwise)
 *             whose {@code deleted_at} is older than
 *             {@code route.cleanup.purge-grace-days} (default 7). Always
 *             runs regardless of the enable flag.</li>
 *       </ul>
 *       Recorder rowsAffected is the sum of both stages.</li>
 *   <li><b>Email-token cleanup</b> (loader name {@code email-token-cleanup})
 *       — delete expired {@code email_verifications} and
 *       {@code password_resets} rows. Always runs regardless of the enable
 *       flag. Recorder rowsAffected is verifications + resets.</li>
 * </ol>
 *
 * <p>Both cron entry points and the manual-trigger entry points
 * ({@link #runRouteCleanup}, {@link #runEmailTokenCleanup}) start with a
 * {@link LoaderRunRecorder#start(String, TriggerType)} call. If another
 * run is already in flight for the same loader name, the recorder throws
 * {@link LoaderRunRecorder.RunInProgressException}; cron entries catch
 * and log-skip, manual entries surface as HTTP 409 from the trigger
 * controller.
 */
@Component
@Slf4j
public class GuestRouteCleanupJob {

    /** Loader name for the {@code loader_runs} entry of the route purge. */
    public static final String ROUTE_CLEANUP_LOADER_NAME = "guest-route-cleanup";

    /** Loader name for the {@code loader_runs} entry of the email-token sweep. */
    public static final String EMAIL_TOKEN_CLEANUP_LOADER_NAME = "email-token-cleanup";

    private final RouteRepository routeRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final UserManagementService userManagementService;
    private final LoaderRunRecorder recorder;

    @Value("${route.cleanup.enabled:true}")
    private boolean guestRouteCleanupEnabled;

    @Value("${route.cleanup.guest-route-retention-days:30}")
    private int retentionDays;

    @Value("${route.cleanup.purge-grace-days:7}")
    private int purgeGraceDays;

    public GuestRouteCleanupJob(RouteRepository routeRepository,
                                EmailVerificationRepository emailVerificationRepository,
                                PasswordResetRepository passwordResetRepository,
                                UserManagementService userManagementService,
                                LoaderRunRecorder recorder) {
        this.routeRepository = routeRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.userManagementService = userManagementService;
        this.recorder = recorder;
    }

    // ------------------------------------------------------------------------
    // Cron entry points — record runs as TriggerType.CRON.
    //
    // Both the cron and the manual paths need a real Spring transaction by
    // the time we hit the @Modifying repo queries. @Transactional on the
    // public methods that actually receive proxy calls is the only place it
    // works: cleanGuestRoutes / cleanExpiredEmailTokens (proxy-entered by
    // @Scheduled) and runRouteCleanup / runEmailTokenCleanup (proxy-entered
    // by AdminLoaderService for the manual trigger).
    //
    // The recorder.start / success / fail calls inside these methods use
    // REQUIRES_NEW so each loader_runs row commits independently of the
    // outer cleanup transaction — a failed cleanup still produces a FAIL
    // row, and a successful one still produces a SUCCESS row.
    // ------------------------------------------------------------------------

    @Scheduled(cron = "${route.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanGuestRoutes() {
        runRouteCleanup(TriggerType.CRON);
    }

    @Scheduled(cron = "${route.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanExpiredEmailTokens() {
        runEmailTokenCleanup(TriggerType.CRON);
    }

    // ------------------------------------------------------------------------
    // Public entry points — used by both the cron methods above and the
    // admin-console manual trigger. The trigger type tells the recorder
    // whether to mark the run CRON or MANUAL.
    // ------------------------------------------------------------------------

    /**
     * Fire the two-stage route purge. If a run for this loader is already
     * RUNNING, the recorder throws {@link
     * LoaderRunRecorder.RunInProgressException}; we catch it on the cron
     * path (log-and-skip), and let it propagate on the manual path so the
     * controller can return HTTP 409.
     *
     * @param trigger {@link TriggerType#CRON} from the scheduler,
     *                {@link TriggerType#MANUAL} from the admin console
     */
    @Transactional
    public void runRouteCleanup(TriggerType trigger) {
        LoaderRun run;
        try {
            run = recorder.start(ROUTE_CLEANUP_LOADER_NAME, trigger);
        } catch (LoaderRunRecorder.RunInProgressException e) {
            if (trigger == TriggerType.CRON) {
                log.info("Route cleanup skipped — another run already in progress.");
                return;
            }
            throw e;
        }

        long start = System.currentTimeMillis();
        try {
            long rowsAffected = doRouteCleanup();
            recorder.success(run, rowsAffected);
            log.info("Route cleanup ({}): {} row(s) affected in {}ms",
                    trigger, rowsAffected, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Route cleanup ({}) failed", trigger, e);
            recorder.fail(run, e);
            if (trigger != TriggerType.CRON) {
                throw e;
            }
        }
    }

    /**
     * Fire the email-token sweep. Same skip / propagate semantics as
     * {@link #runRouteCleanup}.
     */
    @Transactional
    public void runEmailTokenCleanup(TriggerType trigger) {
        LoaderRun run;
        try {
            run = recorder.start(EMAIL_TOKEN_CLEANUP_LOADER_NAME, trigger);
        } catch (LoaderRunRecorder.RunInProgressException e) {
            if (trigger == TriggerType.CRON) {
                log.info("Email-token cleanup skipped — another run already in progress.");
                return;
            }
            throw e;
        }

        long start = System.currentTimeMillis();
        try {
            long rowsAffected = doEmailTokenCleanup();
            recorder.success(run, rowsAffected);
            log.info("Email-token cleanup ({}): {} row(s) affected in {}ms",
                    trigger, rowsAffected, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Email-token cleanup ({}) failed", trigger, e);
            recorder.fail(run, e);
            if (trigger != TriggerType.CRON) {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Actual work — runs inside the ambient transaction set up by the
    // public entry methods above. Kept package-private; their @Transactional
    // here would be a no-op (Spring's proxy-based @Transactional doesn't
    // intercept self-invocations from within the same bean) and would mask
    // the fact that the outer methods are the proxy entry points carrying
    // the real transaction boundary.
    // ------------------------------------------------------------------------

    long doRouteCleanup() {
        ZonedDateTime now = ZonedDateTime.now();
        int softDeleted = 0;
        if (guestRouteCleanupEnabled) {
            UUID guestId = userManagementService.getOrCreateGuestUser().getId();
            ZonedDateTime softCutoff = now.minusDays(retentionDays);
            softDeleted = routeRepository.softDeleteGuestRoutesCreatedBefore(
                    guestId, softCutoff, now);
        } else {
            log.debug("Guest route cleanup disabled (route.cleanup.enabled=false); "
                    + "skipping stage 1 (soft-delete of aged guest routes).");
        }

        ZonedDateTime hardCutoff = now.minusDays(purgeGraceDays);
        int hardDeleted = routeRepository.hardDeleteSoftDeletedBefore(hardCutoff);

        log.debug("Route cleanup detail: soft-deleted {} guest route(s) older than {} day(s); "
                + "hard-deleted {} soft-deleted route(s) past the {} day grace window.",
                softDeleted, retentionDays, hardDeleted, purgeGraceDays);
        return (long) softDeleted + hardDeleted;
    }

    long doEmailTokenCleanup() {
        LocalDateTime cutoff = LocalDateTime.now();
        int verifications = emailVerificationRepository.deleteByExpiresAtBefore(cutoff);
        int resets = passwordResetRepository.deleteByExpiresAtBefore(cutoff);
        log.debug("Email-token cleanup detail: deleted {} verification + {} reset row(s)",
                verifications, resets);
        return (long) verifications + resets;
    }
}
