package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the admin Users list. Returned by {@code GET /api/admin/users};
 * Phase 4 of ADMIN_CONSOLE.md.
 *
 * <p>{@code routeCount} is the user's <em>active</em> route count — soft-deleted
 * routes are excluded because the admin's "this user has N routes" intuition
 * is about live data the user can still see. Soft-deleted routes are still
 * cascaded on user delete; the totals are reported separately in
 * {@link AdminUserDeleteResult}.
 *
 * <p>{@code hasPendingVerification} is {@code true} iff the user has at least
 * one {@code email_verifications} row that is still unconsumed and not yet
 * expired — the indicator that drives the "Force-verify" action's usefulness.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserSummary {

    private UUID id;
    private String email;
    private String name;
    private boolean enabled;
    private LocalDateTime created;
    private long routeCount;
    private boolean hasPendingVerification;
}
