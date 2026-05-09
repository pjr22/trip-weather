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
 * One region the local OpenRouteService instance is authoritative for. The
 * polygon is the Geofabrik clip polygon (.poly file) for the OSM extract the
 * engine is built on, so an ST_Covers hit means the engine has the road
 * network for that point. Used by the dispatch wrapper in RouteService to
 * decide local vs public ORS per request.
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

    @Column(name = "geom", nullable = false, columnDefinition = "geography(MultiPolygon, 4326)")
    private MultiPolygon geom;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
