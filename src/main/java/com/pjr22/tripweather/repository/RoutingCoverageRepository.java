package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.RoutingCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RoutingCoverageRepository extends JpaRepository<RoutingCoverage, String> {

    /**
     * Returns true iff every point in the supplied multipoint WKT is inside an
     * enabled, polygon-populated coverage region. Single round-trip: PostGIS
     * evaluates the coverage union once and checks containment for the whole
     * route. WKT is built by the caller (RoutingDispatcher) as
     * {@code MULTIPOINT(lon1 lat1, lon2 lat2, ...)} from the request waypoints.
     *
     * <p>Three conditions must hold for a row to participate in the union:
     * {@code enabled=TRUE} (admin opted in), {@code geom IS NOT NULL} (the
     * cron has fetched the .poly at least once), and the row exists at all.
     * Empty coverage table → returns false (correct: nothing is covered,
     * every request goes to public ORS).
     */
    @Query(value = """
            SELECT COALESCE(BOOL_AND(ST_Covers(unioned.geom,
                                               (point_dump).geom::geography)),
                            FALSE)
              FROM (SELECT ST_Union(geom::geometry) AS geom
                      FROM routing_coverage
                     WHERE enabled AND geom IS NOT NULL) unioned,
                   ST_Dump(ST_GeomFromText(:wkt, 4326)) point_dump
            """, nativeQuery = true)
    boolean coversAll(@Param("wkt") String multipointWkt);

    /**
     * Atomic upsert of polygon + fetched_at, keyed on the pbf name. The
     * polygon is passed as WKT and rebuilt server-side; SRID 4326 matches
     * the column's {@code geography(MultiPolygon, 4326)} type.
     *
     * <p>Does NOT touch {@code enabled}: that's the admin's manual toggle
     * (set via PATCH /api/admin/pbfs/{name}), so polygon-refresh leaves it
     * alone. On INSERT the column falls back to its DEFAULT (FALSE) — but
     * paired-row creation in {@code PbfFileService.create()} pre-inserts the
     * row with {@code enabled=TRUE} as the opt-out default, so by the time
     * this upsert runs the row already exists with the admin's chosen value.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO routing_coverage (name, geom, enabled, fetched_at)
            VALUES (:name,
                    ST_GeomFromText(:wkt, 4326)::geography,
                    FALSE,
                    :fetchedAt)
            ON CONFLICT (name) DO UPDATE SET
                geom       = EXCLUDED.geom,
                fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    void upsertPolygon(@Param("name") String name,
                       @Param("wkt") String multipolygonWkt,
                       @Param("fetchedAt") LocalDateTime fetchedAt);

    /**
     * Inserts an empty paired row when admin creates a new pbf. The row
     * carries {@code enabled=TRUE} (opt-out default) and {@code geom=NULL};
     * the dispatcher's NULL-geom filter keeps it inert until the cron's
     * next polygon-fetch populates it. ON CONFLICT DO NOTHING so callers
     * don't have to pre-check.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO routing_coverage (name, enabled, fetched_at)
            VALUES (:name, TRUE, NULL)
            ON CONFLICT (name) DO NOTHING
            """, nativeQuery = true)
    void insertEmptyRow(@Param("name") String name);

    /**
     * Flips the admin's dispatcher toggle. Returns the number of rows
     * affected so callers can distinguish "flipped" from "no such row".
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE routing_coverage SET enabled = :enabled WHERE name = :name",
            nativeQuery = true)
    int updateEnabled(@Param("name") String name, @Param("enabled") boolean enabled);

    /**
     * All routing_coverage row names. Used by AdminLoaderService to
     * enumerate {@code ors-coverage:{pbfName}} loader entries on the
     * Loaders card (one per pbf in the 1:1 model).
     */
    @Query(value = "SELECT name FROM routing_coverage ORDER BY name", nativeQuery = true)
    List<String> findAllNames();
}
