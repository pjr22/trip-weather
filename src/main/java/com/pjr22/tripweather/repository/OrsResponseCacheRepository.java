package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.OrsResponseCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrsResponseCacheRepository
        extends JpaRepository<OrsResponseCache, String> {
}
