package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.LocalDateTime;

/**
 * Durable cache of Geoapify /geocode/reverse responses. Insert-only — lookups
 * use ST_DWithin to find the nearest cached point within a small radius.
 */
@Entity
@Table(name = "geocode_reverse_cache")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeocodeReverseCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "point", nullable = false, columnDefinition = "geography(Point, 4326)")
    private Point point;

    @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String responseJson;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
