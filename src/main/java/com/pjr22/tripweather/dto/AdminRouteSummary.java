package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One row of the admin Routes list. Returned by
 * {@code GET /api/admin/routes}; intentionally distinct from the public
 * {@link RouteDto} so the admin shape can carry owner metadata + soft-delete
 * state without forcing those fields onto the user-facing API.
 *
 * <p>{@code ownerKind} is either {@code "USER"} (a real, signed-up account)
 * or {@code "GUEST"} (the shared anonymous owner). {@code deletedAt} is
 * non-null when the row is currently soft-deleted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminRouteSummary {

    private UUID id;
    private String name;
    private String ownerEmail;
    private String ownerKind;
    private long waypointCount;
    private ZonedDateTime created;
    private ZonedDateTime deletedAt;
}
