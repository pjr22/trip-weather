package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.NwsGridpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
