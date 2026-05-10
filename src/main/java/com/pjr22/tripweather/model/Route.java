package com.pjr22.tripweather.model;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * A saved route belonging to a user (the shared guest user for anonymous saves).
 *
 * <p>Soft-delete is enforced at the entity level via {@link SQLRestriction}:
 * every JPA load / find / JPQL query against {@code Route} silently filters
 * out rows where {@code deleted_at IS NOT NULL}. The admin console is the
 * only path that needs to see soft-deleted rows; it bypasses the restriction
 * by going through native SQL (see {@code AdminRouteService}). Phase 1 of
 * ADMIN_CONSOLE.md.
 */
@Entity
@Table(name = "routes")
@SQLRestriction("deleted_at IS NULL")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Route {
    
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;
    
    @Column(name = "name", nullable = false, length = 255)
    private String name;
    
    @Column(name = "created", nullable = false)
    private ZonedDateTime created;

    /**
     * Soft-delete marker. {@code null} means active; non-null is the moment
     * the admin (or the cleanup job's stage 1) marked the route deleted.
     * The entity-level {@link SQLRestriction} hides any row with a non-null
     * value from every JPA-level query.
     */
    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, referencedColumnName = "id",
                foreignKey = @ForeignKey(
                    name = "routes_user_id_fkey",
                    foreignKeyDefinition = "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"))
    private User user;
    
    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<Waypoint> waypoints;
    
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
