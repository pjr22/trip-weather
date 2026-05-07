package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.GeocodeReverseCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GeocodeReverseCacheRepository
        extends JpaRepository<GeocodeReverseCache, Long> {

    /**
     * Returns the cached entry whose point is nearest to (lat, lon) within the
     * given radius in meters. Ties are broken by most-recent {@code fetched_at}
     * so a refresh-inserted row supersedes its older neighbours without us
     * having to delete them.
     */
    @Query(value = """
            SELECT * FROM geocode_reverse_cache
             WHERE ST_DWithin(point, ST_Point(:lon, :lat)::geography, :radiusMeters)
             ORDER BY ST_Distance(point, ST_Point(:lon, :lat)::geography) ASC,
                      fetched_at DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<GeocodeReverseCache> findNearest(@Param("lon") double lon,
                                              @Param("lat") double lat,
                                              @Param("radiusMeters") double radiusMeters);
}
