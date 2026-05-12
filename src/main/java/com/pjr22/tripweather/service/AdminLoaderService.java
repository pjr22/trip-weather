package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.LoaderRunDto;
import com.pjr22.tripweather.dto.LoaderSummaryDto;
import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.repository.LoaderRunRepository;
import com.pjr22.tripweather.repository.PbfFileRepository;
import com.pjr22.tripweather.routing.GeofabrikCoverageLoader;
import com.pjr22.tripweather.scheduler.GuestRouteCleanupJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Backs {@link com.pjr22.tripweather.controller.AdminLoaderController}.
 *
 * <ul>
 *   <li>{@link #listLoaders()} — one summary per known loader name (the
 *       statically-declared cleanup + EV loaders, plus per-region ORS
 *       coverage loaders, plus any historical-only names with rows in
 *       {@code loader_runs}).</li>
 *   <li>{@link #history(String, int)} — paginated history for one loader.</li>
 *   <li>{@link #triggerByName(String)} — pre-checks the concurrency guard
 *       in the calling thread (so HTTP 409 surfaces synchronously, not
 *       silently to a background log), then dispatches the actual work
 *       async. The loader being triggered owns its own
 *       {@code recorder.start} → {@code success}/{@code fail} cycle —
 *       this service does NOT pre-create the {@code RUNNING} row.</li>
 *   <li>{@link #refreshAllCoverageRegions()} — fans the coverage refresh
 *       over every configured region, sequentially, on a single
 *       background task. Per-region failures and conflicts are logged;
 *       the loop keeps going.</li>
 * </ul>
 *
 * <p>Phase 2 of ADMIN_CONSOLE.md.
 *
 * <p><b>On the response shape of triggers:</b> the trigger endpoint
 * returns 202 with no run id, because the {@code RUNNING} row is
 * created by the loader on the async thread (after the controller has
 * already returned). The frontend polls {@code GET /api/admin/loaders}
 * to observe the new RUNNING row appear, then watches it transition.
 * This keeps the loader classes single-source-of-truth for their
 * recorder lifecycle and avoids a double-start race that a service-side
 * pre-create would introduce.
 */
@Service
@Slf4j
public class AdminLoaderService {

    /** Max page size for the history endpoint; caps any explicit larger
     *  ?limit= so a single request can't pull the whole table. */
    public static final int HISTORY_MAX_LIMIT = 200;
    public static final int HISTORY_DEFAULT_LIMIT = 20;

    private final LoaderRunRepository runRepository;
    private final GuestRouteCleanupJob cleanupJob;
    private final EvStationLoader evStationLoader;
    private final ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider;
    private final PbfFileRepository pbfFileRepository;

    public AdminLoaderService(LoaderRunRepository runRepository,
                              GuestRouteCleanupJob cleanupJob,
                              EvStationLoader evStationLoader,
                              ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider,
                              PbfFileRepository pbfFileRepository) {
        this.runRepository = runRepository;
        this.cleanupJob = cleanupJob;
        this.evStationLoader = evStationLoader;
        this.coverageLoaderProvider = coverageLoaderProvider;
        this.pbfFileRepository = pbfFileRepository;
    }

    @Transactional(readOnly = true)
    public List<LoaderSummaryDto> listLoaders() {
        // LinkedHashMap preserves insertion order so the data view's three
        // cards (cleanup → data → coverage) get loaders in declaration
        // order. Historical-only names tack on at the end.
        Map<String, String> known = new LinkedHashMap<>();
        known.put(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME, "cleanup");
        known.put(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME, "cleanup");
        known.put(EvStationLoader.LOADER_NAME, "data");

        // Phase 2c: one ors-coverage:* loader per pbf row, in pbf_name order.
        // The loader bean's availability still gates whether we surface
        // them (trip.local.ors.enabled=true → bean present → loaders shown).
        if (coverageLoaderProvider.getIfAvailable() != null) {
            for (String pbfName : pbfNamesSorted()) {
                known.put(GeofabrikCoverageLoader.loaderName(pbfName), "coverage");
            }
        }

        // Fold in historical-only names (e.g. ors-coverage:texas after the
        // texas pbf row was deleted) so their history isn't orphaned in
        // the UI.
        for (String historical : runRepository.findDistinctLoaderNames()) {
            if (!known.containsKey(historical)) {
                known.put(historical, categoryOf(historical));
            }
        }

        List<LoaderSummaryDto> out = new ArrayList<>(known.size());
        known.forEach((name, category) -> {
            Optional<LoaderRun> last = runRepository
                    .findFirstByLoaderNameOrderByStartedAtDesc(name);
            out.add(new LoaderSummaryDto(name, category, LoaderRunDto.from(last.orElse(null))));
        });
        return out;
    }

    @Transactional(readOnly = true)
    public List<LoaderRunDto> history(String loaderName, int limit) {
        return runRepository.findByLoaderNameOrderByStartedAtDesc(
                        loaderName, PageRequest.of(0, clampLimit(limit)))
                .getContent()
                .stream()
                .map(LoaderRunDto::from)
                .toList();
    }

    /**
     * Manual trigger by loader name. Pre-checks the concurrency guard in
     * the calling thread, then dispatches the loader's own
     * recorder-aware entry point on a background task.
     *
     * @throws LoaderRunRecorder.RunInProgressException if a run for this
     *         loader is already in flight (controller maps to HTTP 409)
     * @throws IllegalArgumentException if {@code loaderName} doesn't
     *         match any known loader (controller maps to HTTP 404)
     * @throws IllegalStateException if local ORS is disabled but the
     *         caller asked to trigger an ors-coverage loader
     *         (controller maps to HTTP 503)
     */
    public void triggerByName(String loaderName) {
        if (loaderName == null) {
            throw new IllegalArgumentException("Loader name is required");
        }

        // Pre-check synchronously: surface 409 in the calling thread, not
        // 30 ms later in a log line nobody reads. The loader's own
        // recorder.start is still authoritative — the unique partial
        // index handles the unlikely TOCTOU race.
        if (runRepository.existsByLoaderNameAndStatus(loaderName, LoaderRun.Status.RUNNING)) {
            throw new LoaderRunRecorder.RunInProgressException(loaderName);
        }

        Runnable work = resolveWork(loaderName);
        CompletableFuture.runAsync(() -> {
            try {
                work.run();
            } catch (LoaderRunRecorder.RunInProgressException e) {
                // Lost the unique-index race to a concurrent caller. Pre-check
                // already returned 202; nothing left to surface to the client.
                log.info("Manual trigger for '{}' lost race to another run", loaderName);
            } catch (Exception e) {
                // The loader already recorded a FAIL row before re-throwing;
                // we just keep the background thread from swallowing silently.
                log.warn("Manual trigger for '{}' failed: {}", loaderName, e.getMessage());
            }
        });
    }

    /**
     * Refresh every known pbf's coverage polygon sequentially on a single
     * background task. Per-pbf failures (network, parse) and conflicts
     * (already refreshing) are logged; the loop keeps going. Returns the
     * list of pbf names that were enqueued so the controller's 202 body
     * can confirm scope.
     *
     * <p>Phase 2c: the pbf list comes from {@code pbf_files} instead of
     * the retired {@code trip.routing.local-regions} property. Snapshot is
     * taken in the calling thread before the async dispatch so the loop
     * sees a consistent set even if the admin edits the table mid-flight.
     *
     * @throws IllegalStateException if local ORS is disabled
     */
    public List<String> refreshAllCoverageRegions() {
        GeofabrikCoverageLoader coverageLoader = coverageLoaderProvider.getIfAvailable();
        if (coverageLoader == null) {
            throw new IllegalStateException(
                    "Local ORS is not enabled (trip.local.ors.enabled=false); "
                  + "coverage loader is unavailable.");
        }
        List<String> pbfNames = pbfNamesSorted();
        CompletableFuture.runAsync(() -> {
            for (String pbfName : pbfNames) {
                try {
                    coverageLoader.refresh(pbfName, TriggerType.MANUAL);
                } catch (LoaderRunRecorder.RunInProgressException e) {
                    log.info("Refresh-all: skipped pbf '{}' (already in progress)", pbfName);
                } catch (Exception e) {
                    log.warn("Refresh-all: pbf '{}' failed: {}",
                            pbfName, e.getMessage());
                }
            }
            log.info("Refresh-all: completed sequential refresh of {} pbf(s)",
                    pbfNames.size());
        });
        return pbfNames;
    }

    private Runnable resolveWork(String loaderName) {
        if (GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME.equals(loaderName)) {
            return () -> cleanupJob.runRouteCleanup(TriggerType.MANUAL);
        }
        if (GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME.equals(loaderName)) {
            return () -> cleanupJob.runEmailTokenCleanup(TriggerType.MANUAL);
        }
        if (EvStationLoader.LOADER_NAME.equals(loaderName)) {
            return () -> evStationLoader.runWithRetryOnFailure(TriggerType.MANUAL);
        }
        if (loaderName.startsWith(GeofabrikCoverageLoader.LOADER_NAME_PREFIX)) {
            return resolveCoverageWork(
                    loaderName.substring(GeofabrikCoverageLoader.LOADER_NAME_PREFIX.length()));
        }
        throw new IllegalArgumentException("Unknown loader: " + loaderName);
    }

    private Runnable resolveCoverageWork(String pbfName) {
        GeofabrikCoverageLoader coverageLoader = coverageLoaderProvider.getIfAvailable();
        if (coverageLoader == null) {
            throw new IllegalStateException(
                    "Local ORS is not enabled (trip.local.ors.enabled=false); "
                  + "coverage loader is unavailable.");
        }
        // Phase 2c: the validation source is pbf_files, not a config list.
        if (!pbfFileRepository.existsById(pbfName)) {
            throw new IllegalArgumentException("Pbf '" + pbfName
                    + "' is not in pbf_files");
        }
        return () -> coverageLoader.refresh(pbfName, TriggerType.MANUAL);
    }

    /** Pbf names in {@code pbf_name} order — the canonical source for
     *  enumerating per-pbf coverage loaders since Phase 2c. */
    private List<String> pbfNamesSorted() {
        return pbfFileRepository.findAll().stream()
                .map(p -> p.getPbfName())
                .sorted()
                .toList();
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) return HISTORY_DEFAULT_LIMIT;
        return Math.min(requested, HISTORY_MAX_LIMIT);
    }

    private static String categoryOf(String loaderName) {
        if (loaderName.startsWith(GeofabrikCoverageLoader.LOADER_NAME_PREFIX)) {
            return "coverage";
        }
        if (loaderName.equals(EvStationLoader.LOADER_NAME)) {
            return "data";
        }
        return "cleanup";
    }
}
