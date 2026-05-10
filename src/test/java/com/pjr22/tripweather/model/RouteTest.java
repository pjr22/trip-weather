package com.pjr22.tripweather.model;

import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural assertion that the Route entity carries the soft-delete
 * {@link SQLRestriction}. This is the single piece of metadata the entire
 * "soft-delete is invisible to JPA" guarantee depends on; if it's removed
 * (or its predicate edited) every JPA path that today excludes deleted
 * rows would silently start surfacing them. That is a destructive
 * regression that integration tests would catch only after the leak —
 * checking the annotation directly here keeps the safety net in unit
 * tests, where the failure mode is obvious.
 */
class RouteTest {

    @Test
    void routeEntityHasSqlRestrictionFilteringSoftDeletedRows() {
        SQLRestriction restriction = Route.class.getAnnotation(SQLRestriction.class);
        assertThat(restriction)
                .as("Route.class must carry @SQLRestriction so soft-deleted rows are "
                  + "hidden from every JPA path; admin paths bypass this via native SQL")
                .isNotNull();
        assertThat(restriction.value()).isEqualTo("deleted_at IS NULL");
    }
}
