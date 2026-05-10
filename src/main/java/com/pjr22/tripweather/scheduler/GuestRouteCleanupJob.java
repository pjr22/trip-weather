package com.pjr22.tripweather.scheduler;

import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.RouteRepository;
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
 * ({@code route.cleanup.cron}, default daily at 03:00):
 * <ol>
 *   <li><b>Two-stage route purge</b> (Phase 1 of ADMIN_CONSOLE.md, was a
 *       one-shot hard delete pre-Phase 1):
 *       <ul>
 *         <li>Stage 1 — soft-delete guest-owned routes whose {@code created}
 *             is older than {@code route.cleanup.guest-route-retention-days}
 *             (default 30) and that aren't already marked deleted.
 *             <b>Gated</b> by {@code route.cleanup.enabled} so operators who
 *             want to keep guest data around can flip it off without
 *             disabling the rest of the job.</li>
 *         <li>Stage 2 — hard-delete <em>any</em> route (guest or otherwise)
 *             whose {@code deleted_at} is older than
 *             {@code route.cleanup.purge-grace-days} (default 7). This is
 *             how an admin's soft-delete eventually becomes permanent: a
 *             7-day undo window before the row + waypoints disappear via
 *             the {@code waypoints.route_id ON DELETE CASCADE}. Always
 *             runs (no enable gate) — the grace window is the safety net.</li>
 *       </ul></li>
 *   <li><b>Email-token cleanup</b> — delete expired
 *       {@code email_verifications} and {@code password_resets} rows.
 *       <b>Always runs</b>; these tokens are useless past their
 *       {@code expires_at} and there's no reason to leave them in the table.</li>
 * </ol>
 *
 * <p>Authenticated users' routes are never auto-soft-deleted by stage 1 —
 * only the shared guest user's routes age out. Stage 2 is owner-agnostic
 * because it works on the {@code deleted_at} column, which only carries a
 * value when stage 1 or an admin set it explicitly.
 */
@Component
@Slf4j
public class GuestRouteCleanupJob {

    private final RouteRepository routeRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final UserManagementService userManagementService;

    @Value("${route.cleanup.enabled:true}")
    private boolean guestRouteCleanupEnabled;

    @Value("${route.cleanup.guest-route-retention-days:30}")
    private int retentionDays;

    @Value("${route.cleanup.purge-grace-days:7}")
    private int purgeGraceDays;

    public GuestRouteCleanupJob(RouteRepository routeRepository,
                                EmailVerificationRepository emailVerificationRepository,
                                PasswordResetRepository passwordResetRepository,
                                UserManagementService userManagementService) {
        this.routeRepository = routeRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.userManagementService = userManagementService;
    }

    /**
     * Run both stages of the route purge. Stage 1 (soft-delete of aged guest
     * routes) is gated by {@code route.cleanup.enabled}; stage 2 (hard-delete
     * past the grace window) always runs so the admin's manual soft-deletes
     * still age out even when the guest sweep is disabled.
     */
    @Scheduled(cron = "${route.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanGuestRoutes() {
        long start = System.currentTimeMillis();
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

        log.info("Route cleanup: stage 1 soft-deleted {} guest route(s) older than {} day(s); "
                + "stage 2 hard-deleted {} soft-deleted route(s) past the {} day grace window. "
                + "Total {}ms.",
                softDeleted, retentionDays,
                hardDeleted, purgeGraceDays,
                System.currentTimeMillis() - start);
    }

    /**
     * Sweep expired email-verification and password-reset tokens. Always
     * runs — these tokens are useless past their {@code expires_at} and
     * there's no operator-visible reason to keep them.
     */
    @Scheduled(cron = "${route.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanExpiredEmailTokens() {
        long start = System.currentTimeMillis();
        LocalDateTime cutoff = LocalDateTime.now();
        int verifications = emailVerificationRepository.deleteByExpiresAtBefore(cutoff);
        int resets = passwordResetRepository.deleteByExpiresAtBefore(cutoff);
        log.info("Email-token cleanup: deleted {} verification + {} reset row(s) in {}ms",
                verifications, resets, System.currentTimeMillis() - start);
    }
}
