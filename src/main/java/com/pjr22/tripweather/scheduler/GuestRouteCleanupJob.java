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
 *   <li>Delete guest-owned routes (and their waypoints, via the FK cascade
 *       from Phase 1) that are older than the retention window. Defaults
 *       to 30 days; configurable via {@code route.cleanup.guest-route-retention-days}.
 *       <b>Gated</b> by {@code route.cleanup.enabled} — operators who want to
 *       keep guest data around can flip it off without disabling token
 *       cleanup.</li>
 *   <li>Delete expired email-verification and password-reset tokens
 *       ({@code email_verifications}, {@code password_resets}). <b>Always
 *       runs</b> — these tokens are useless past their {@code expires_at}
 *       and there's no reason to leave them in the table.</li>
 * </ol>
 *
 * <p>Authenticated users' routes are never auto-deleted — only routes whose
 * owner is the shared guest account.
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
     * Sweep guest-owned routes older than the retention window. Single bulk
     * {@code DELETE} at the JPQL level — waypoints follow via the database's
     * {@code ON DELETE CASCADE} on {@code waypoints.route_id}.
     */
    @Scheduled(cron = "${route.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanGuestRoutes() {
        if (!guestRouteCleanupEnabled) {
            log.debug("Guest route cleanup disabled (route.cleanup.enabled=false); skipping.");
            return;
        }
        long start = System.currentTimeMillis();
        UUID guestId = userManagementService.getOrCreateGuestUser().getId();
        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(retentionDays);
        int deleted = routeRepository.deleteByUserIdAndCreatedBefore(guestId, cutoff);
        log.info("Guest route cleanup: deleted {} route(s) older than {} day(s) in {}ms",
                deleted, retentionDays, System.currentTimeMillis() - start);
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
