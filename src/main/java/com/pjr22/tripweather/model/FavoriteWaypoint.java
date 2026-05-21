package com.pjr22.tripweather.model;

import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * A favorite waypoint — a saved place belonging to an authenticated user.
 * Independent of any route: deleting a route never orphans favorites, and
 * favorites can be added to any route at any time.
 *
 * <p>Soft-delete is enforced at the entity level via {@link SQLRestriction}:
 * every JPA load / find / JPQL query against {@code FavoriteWaypoint} silently
 * filters out rows where {@code deleted_at IS NOT NULL}. The admin console
 * (Phase 5 of FAVORITES_AND_ROUTE_MGMT.md) will be the only path that needs
 * to see soft-deleted rows; it will bypass the restriction by going through
 * native SQL — mirroring the Route / AdminRouteService pattern.
 *
 * <p>The {@code uq_favorite_waypoints_user_label} unique index is a partial
 * index keyed on {@code (user_id, LOWER(label)) WHERE deleted_at IS NULL},
 * so a label freed by soft-delete becomes reusable for new favorites.
 */
@Entity
@Table(name = "favorite_waypoints")
@SQLRestriction("deleted_at IS NULL")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteWaypoint {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, referencedColumnName = "id",
                foreignKey = @ForeignKey(
                    name = "favorite_waypoints_user_id_fkey",
                    foreignKeyDefinition = "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"))
    private User user;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "location_name", nullable = false, length = 1023)
    private String locationName;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "elevation")
    private Double elevation;

    /**
     * IANA timezone name for this place (e.g. {@code "America/Denver"}).
     * Nullable — if the caller didn't have timezone data when the favorite
     * was created, this stays null and the consumer must resolve it from
     * lat/lon on demand. When present, the four sibling columns
     * ({@code timezone*Offset}, {@code timezone*Abbr}) should also be set
     * so the SPA can render times without a follow-up API call.
     */
    @Column(name = "timezone_name", length = 255)
    private String timezoneName;

    @Column(name = "timezone_std_offset", length = 64)
    private String timezoneStdOffset;

    @Column(name = "timezone_dst_offset", length = 64)
    private String timezoneDstOffset;

    @Column(name = "timezone_std_abbr", length = 16)
    private String timezoneStdAbbr;

    @Column(name = "timezone_dst_abbr", length = 16)
    private String timezoneDstAbbr;

    @Column(name = "created", nullable = false)
    private ZonedDateTime created;

    /**
     * Soft-delete marker. {@code null} means active; non-null is the moment
     * the user (or admin) marked the favorite deleted. The entity-level
     * {@link SQLRestriction} hides any row with a non-null value from every
     * JPA-level query.
     */
    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (created == null) {
            created = ZonedDateTime.now();
        }
    }
}
