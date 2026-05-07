package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.EvStation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for {@link EvStation}. Used only for the loader's
 * bootstrap-on-empty check ({@code count()}); the user-facing
 * spatial-and-filter query lives in {@link EvStationQueryDao} where dynamic
 * SQL composition is cleaner than a static {@code @Query}.
 */
@Repository
public interface EvStationRepository extends JpaRepository<EvStation, Long> {
}
