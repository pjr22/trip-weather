package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.NwsGridpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface NwsGridpointRepository
        extends JpaRepository<NwsGridpoint, NwsGridpoint.GridpointId> {

    /**
     * Returns the cached gridpoint whose polygon contains the given point, if
     * any. ST_Covers (rather than ST_Contains) treats boundary points as
     * inside; NWS gridpoint cells tessellate without overlap, so the LIMIT 1
     * is defensive — at most one row should match.
     */
    @Query(value = """
            SELECT * FROM nws_gridpoints
             WHERE ST_Covers(geom, ST_Point(:lon, :lat)::geography)
             LIMIT 1
            """, nativeQuery = true)
    Optional<NwsGridpoint> findContainingPoint(@Param("lon") double lon,
                                               @Param("lat") double lat);

    /**
     * Atomic upsert keyed on the (office, grid_x, grid_y) primary key.
     * Used in place of {@code save()} so concurrent waypoint requests that
     * resolve to the same NWS grid cell don't race on INSERT. The polygon
     * is passed as WKT and rebuilt server-side; SRID 4326 matches the
     * column's {@code geography(Polygon, 4326)} type.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO nws_gridpoints
                (office, grid_x, grid_y, geom, forecast_url, hourly_url, fetched_at)
            VALUES (:office, :gridX, :gridY,
                    ST_GeomFromText(:wkt, 4326)::geography,
                    :forecastUrl, :hourlyUrl, :fetchedAt)
            ON CONFLICT (office, grid_x, grid_y) DO UPDATE SET
                geom         = EXCLUDED.geom,
                forecast_url = EXCLUDED.forecast_url,
                hourly_url   = EXCLUDED.hourly_url,
                fetched_at   = EXCLUDED.fetched_at
            """, nativeQuery = true)
    void upsert(@Param("office") String office,
                @Param("gridX") int gridX,
                @Param("gridY") int gridY,
                @Param("wkt") String polygonWkt,
                @Param("forecastUrl") String forecastUrl,
                @Param("hourlyUrl") String hourlyUrl,
                @Param("fetchedAt") LocalDateTime fetchedAt);
}
