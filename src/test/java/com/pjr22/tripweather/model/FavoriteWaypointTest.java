package com.pjr22.tripweather.model;

import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural assertion that the FavoriteWaypoint entity carries the
 * soft-delete {@link SQLRestriction}. Mirrors {@link RouteTest} — the
 * annotation is the single piece of metadata the entire "soft-delete is
 * invisible to JPA" guarantee depends on. Removing or editing its
 * predicate would silently surface deleted favorites everywhere; this
 * check keeps that safety net in unit tests.
 */
class FavoriteWaypointTest {

    @Test
    void favoriteWaypointEntityHasSqlRestrictionFilteringSoftDeletedRows() {
        SQLRestriction restriction = FavoriteWaypoint.class.getAnnotation(SQLRestriction.class);
        assertThat(restriction)
                .as("FavoriteWaypoint.class must carry @SQLRestriction so soft-deleted rows are "
                  + "hidden from every JPA path; admin paths bypass this via native SQL")
                .isNotNull();
        assertThat(restriction.value()).isEqualTo("deleted_at IS NULL");
    }
}
