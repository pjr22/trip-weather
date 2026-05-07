package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Polygon;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Durable cache of api.weather.gov /points/{lat,lon} responses. The polygon
 * is the 2.5 km gridpoint cell as returned by NWS; lookups use ST_Covers so a
 * query point gets the correct gridpoint, not a quantized neighbor.
 */
@Entity
@Table(name = "nws_gridpoints")
@IdClass(NwsGridpoint.GridpointId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NwsGridpoint {

    @Id
    @Column(name = "office", nullable = false, length = 8)
    private String office;

    @Id
    @Column(name = "grid_x", nullable = false)
    private int gridX;

    @Id
    @Column(name = "grid_y", nullable = false)
    private int gridY;

    @Column(name = "geom", nullable = false, columnDefinition = "geography(Polygon, 4326)")
    private Polygon geom;

    @Column(name = "forecast_url", nullable = false, columnDefinition = "TEXT")
    private String forecastUrl;

    @Column(name = "hourly_url", nullable = false, columnDefinition = "TEXT")
    private String hourlyUrl;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GridpointId implements Serializable {
        private String office;
        private int gridX;
        private int gridY;
    }
}
