package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.LoaderRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link LoaderRun}. Phase 2 of ADMIN_CONSOLE.md.
 *
 * <p>The history endpoint reads via {@link #findByLoaderNameOrderByStartedAtDesc};
 * the loader-list endpoint reads via {@link #findFirstByLoaderNameOrderByStartedAtDesc}
 * to get the most-recent run per loader. The concurrency guard relies on the
 * partial unique index on {@code (loader_name) WHERE status='RUNNING'} (DDL
 * in the admin-console migration script), not on a method here — the recorder
 * just catches {@link org.springframework.dao.DataIntegrityViolationException}.
 */
@Repository
public interface LoaderRunRepository extends JpaRepository<LoaderRun, Long> {

    /**
     * Most-recent run for a given loader, or empty if it has never run.
     * Used by the loader-list endpoint to summarise each loader's last
     * status without pulling its full history.
     */
    Optional<LoaderRun> findFirstByLoaderNameOrderByStartedAtDesc(String loaderName);

    /**
     * Paginated history of runs for one loader, newest first. Backs
     * {@code GET /api/admin/loaders/{name}/runs}.
     */
    Page<LoaderRun> findByLoaderNameOrderByStartedAtDesc(String loaderName, Pageable pageable);

    /**
     * Distinct loader names that have ever recorded a run. Used to surface
     * historical loader names in the list endpoint (e.g. {@code
     * ors-coverage:texas} from a previous regions config that's no longer
     * in {@code trip.routing.local-regions}).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT r.loaderName FROM LoaderRun r ORDER BY r.loaderName")
    List<String> findDistinctLoaderNames();

    /**
     * Whether a RUNNING row currently exists for the given loader. Pre-check
     * companion to the partial unique index — gives us a clean
     * {@link com.pjr22.tripweather.service.LoaderRunRecorder.RunInProgressException}
     * in the common case without hitting the DB constraint, while the index
     * remains the authoritative TOCTOU-safe guard.
     */
    boolean existsByLoaderNameAndStatus(String loaderName, LoaderRun.Status status);
}
