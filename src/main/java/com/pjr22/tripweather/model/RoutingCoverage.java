package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.MultiPolygon;

import java.time.LocalDateTime;

/**
 * One pbf the local OpenRouteService instance is authoritative for. The
 * polygon is the Geofabrik clip polygon (.poly file) for the OSM extract the
 * engine is built on, so an ST_Covers hit means the engine has the road
 * network for that point. Used by the dispatch wrapper in RouteService to
 * decide local vs public ORS per request.
 *
 * <p>Phase 2c: 1:1 with pbf_files. The {@link #name} column doubles as the
 * FK back to {@code pbf_files.pbf_name} (ON DELETE CASCADE). Two
 * independent admin-controlled flags drive different machinery:
 * {@code pbf_files.active} gates the cron's processing schedule;
 * {@link #enabled} (this entity) gates the dispatcher's local-vs-public
 * decision. Polygon-refresh writes {@link #geom} + {@link #fetchedAt} only —
 * never touches {@link #enabled}, so the admin's toggle sticks across
 * refreshes.
 *
 * <p>{@link #geom} and {@link #fetchedAt} are nullable: a fresh row exists
 * before the cron has fetched the .poly. The dispatcher's {@code coversAll()}
 * filters {@code geom IS NOT NULL} so such rows are inert.
 */
@Entity
@Table(name = "routing_coverage")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoutingCoverage {

    @Id
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "geom", columnDefinition = "geography(MultiPolygon, 4326)")
    private MultiPolygon geom;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
