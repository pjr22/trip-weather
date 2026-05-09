package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.RoutingCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface RoutingCoverageRepository extends JpaRepository<RoutingCoverage, String> {

    /**
     * Returns true iff every point in the supplied multipoint WKT is inside an
     * enabled coverage region. Single round-trip: PostGIS evaluates the
     * coverage union once and checks containment for the whole route. WKT is
     * built by the caller (RoutingDispatcher) as
     * {@code MULTIPOINT(lon1 lat1, lon2 lat2, ...)} from the request waypoints.
     *
     * Empty coverage table → returns false (correct: nothing is covered, every
     * request goes to public ORS).
     */
    @Query(value = """
            SELECT COALESCE(BOOL_AND(ST_Covers(unioned.geom,
                                               (point_dump).geom::geography)),
                            FALSE)
              FROM (SELECT ST_Union(geom::geometry) AS geom
                      FROM routing_coverage WHERE enabled) unioned,
                   ST_Dump(ST_GeomFromText(:wkt, 4326)) point_dump
            """, nativeQuery = true)
    boolean coversAll(@Param("wkt") String multipointWkt);

    /**
     * Atomic upsert keyed on the region name. The polygon is passed as WKT and
     * rebuilt server-side; SRID 4326 matches the column's
     * {@code geography(MultiPolygon, 4326)} type.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO routing_coverage (name, geom, enabled, fetched_at)
            VALUES (:name,
                    ST_GeomFromText(:wkt, 4326)::geography,
                    TRUE,
                    :fetchedAt)
            ON CONFLICT (name) DO UPDATE SET
                geom       = EXCLUDED.geom,
                enabled    = TRUE,
                fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    void upsert(@Param("name") String name,
                @Param("wkt") String multipolygonWkt,
                @Param("fetchedAt") LocalDateTime fetchedAt);
}
