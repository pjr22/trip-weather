package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.PbfFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link PbfFile}. Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>Read-mostly from the admin endpoints; the host-side cron reads + writes
 * the same rows. Both sides pull whole rows — no projections needed.
 */
@Repository
public interface PbfFileRepository extends JpaRepository<PbfFile, String> {

    /** Pbfs the operator hasn't paused. Cron reads this; admin UI shows
     *  both active and inactive (with a visual marker for inactive). */
    List<PbfFile> findByActiveTrueOrderByPbfNameAsc();
}
