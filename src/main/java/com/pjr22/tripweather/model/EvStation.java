package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Local mirror of one NREL Alternative Fuels Data Center station. Refreshed
 * weekly from {@code /api/alt-fuel-stations/v1.json?fuel_type=ELEC} by
 * {@link com.pjr22.tripweather.service.EvStationLoader}; queried by
 * {@link com.pjr22.tripweather.service.EVChargingStationService} via
 * {@code ST_DWithin} against the route LINESTRING.
 *
 * <p>The structured columns are the ones the user-side query filters on; the
 * full upstream {@code properties} block lives in {@code properties} JSONB so
 * the response we return matches the shape NREL would have returned.
 *
 * <p>{@code ev_connector_types} is a Postgres TEXT[] used only in the WHERE
 * clause via {@code &&} (array overlap); JPA reads everything else from this
 * entity, while bulk loader writes go through {@code JdbcTemplate} to keep
 * the array bind explicit.
 */
@Entity
@Table(name = "ev_stations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvStation {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "location", nullable = false, columnDefinition = "geography(Point, 4326)")
    private Point location;

    @Column(name = "fuel_type_code", length = 8)
    private String fuelTypeCode;

    @Column(name = "status_code", length = 8)
    private String statusCode;

    @Column(name = "access_code", length = 16)
    private String accessCode;

    @Column(name = "ev_network", length = 64)
    private String evNetwork;

    @Column(name = "ev_dc_fast_num")
    private Integer evDcFastNum;

    @Column(name = "ev_level1_evse_num")
    private Integer evLevel1EvseNum;

    @Column(name = "ev_level2_evse_num")
    private Integer evLevel2EvseNum;

    @Column(name = "properties", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String properties;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
