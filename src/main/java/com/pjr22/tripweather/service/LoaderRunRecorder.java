package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.Status;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.repository.LoaderRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Records lifecycle transitions for one loader run: RUNNING on start, then
 * SUCCESS or FAIL when the work completes. Phase 2 of ADMIN_CONSOLE.md.
 *
 * <p>Each method runs in its own {@code REQUIRES_NEW} transaction so the
 * row is durably committed independently of whatever the wrapped loader
 * does. Without that, a loader that throws would roll back its own
 * transaction along with the RUNNING row, and we'd lose the failure
 * record entirely (defeating the whole point of the table).
 *
 * <p>Concurrency is enforced at two layers:
 * <ol>
 *   <li>{@link #start} pre-checks
 *       {@link LoaderRunRepository#existsByLoaderNameAndStatus} so the
 *       common case ("manual trigger fired while a run is already going")
 *       gets a clean {@link RunInProgressException} without hitting a DB
 *       constraint violation.</li>
 *   <li>The partial unique index
 *       {@code loader_runs_running_unique_idx} (declared in the migration
 *       script) provides a TOCTOU-safe backstop. If the pre-check passes
 *       but a concurrent caller inserts first, the second insert raises
 *       {@link DataIntegrityViolationException}, which {@code start}
 *       converts to the same {@link RunInProgressException}.</li>
 * </ol>
 */
@Service
@Slf4j
public class LoaderRunRecorder {

    /** Stack-trace summaries can run long; cap so a misbehaving loader
     *  doesn't bloat the row past what the admin UI can comfortably show. */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 4000;

    private final LoaderRunRepository repository;

    public LoaderRunRecorder(LoaderRunRepository repository) {
        this.repository = repository;
    }

    /**
     * Insert a RUNNING row for the loader. Throws
     * {@link RunInProgressException} if another run for the same loader
     * is already RUNNING — controllers map this to HTTP 409, scheduled
     * entry points log-and-skip.
     *
     * @param loaderName logical name (e.g. {@code "guest-route-cleanup"},
     *                   {@code "ors-coverage:colorado"})
     * @param trigger    how the run was triggered
     * @return the persisted, RUNNING run with its generated id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoaderRun start(String loaderName, TriggerType trigger) {
        if (repository.existsByLoaderNameAndStatus(loaderName, Status.RUNNING)) {
            throw new RunInProgressException(loaderName);
        }
        LoaderRun run = new LoaderRun();
        run.setLoaderName(loaderName);
        run.setTriggerType(trigger);
        run.setStatus(Status.RUNNING);
        run.setStartedAt(ZonedDateTime.now());
        try {
            return repository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index caught a race between the pre-check
            // above and our INSERT. Surface the same exception the caller
            // would have seen if the pre-check had hit.
            throw new RunInProgressException(loaderName, e);
        }
    }

    /**
     * Transition the run to SUCCESS with a row count. Re-fetches by id so a
     * stale/detached reference from the calling thread doesn't cause issues
     * (the run was created in a separate {@code REQUIRES_NEW} transaction).
     *
     * @param run          the run returned by {@link #start}
     * @param rowsAffected count to record (0 is fine for loaders without
     *                     a meaningful count, e.g. {@code ors-coverage})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(LoaderRun run, long rowsAffected) {
        Optional<LoaderRun> persisted = repository.findById(run.getId());
        if (persisted.isEmpty()) {
            log.warn("LoaderRunRecorder.success: run {} not found, skipping",
                    run.getId());
            return;
        }
        LoaderRun r = persisted.get();
        r.setStatus(Status.SUCCESS);
        r.setFinishedAt(ZonedDateTime.now());
        r.setRowsAffected(rowsAffected);
        repository.save(r);
    }

    /**
     * Transition the run to FAIL, recording a truncated error message.
     * The exception itself is not re-thrown — recording is best-effort
     * cleanup; the caller has already lost the operation and will surface
     * its own error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(LoaderRun run, Throwable error) {
        Optional<LoaderRun> persisted = repository.findById(run.getId());
        if (persisted.isEmpty()) {
            log.warn("LoaderRunRecorder.fail: run {} not found, skipping",
                    run.getId());
            return;
        }
        LoaderRun r = persisted.get();
        r.setStatus(Status.FAIL);
        r.setFinishedAt(ZonedDateTime.now());
        r.setErrorMessage(formatError(error));
        repository.save(r);
    }

    private static String formatError(Throwable error) {
        if (error == null) {
            return "(no error message)";
        }
        String message = error.getClass().getSimpleName()
                + ": "
                + (error.getMessage() != null ? error.getMessage() : "");
        if (message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * Thrown when a {@link #start} attempt collides with an already-RUNNING
     * row for the same loader. Controllers translate to HTTP 409;
     * scheduled entry points catch and log-skip so a slow manual run
     * doesn't cause a missed cron tick to error.
     */
    public static class RunInProgressException extends RuntimeException {
        public RunInProgressException(String loaderName) {
            super("A run for loader '" + loaderName + "' is already in progress");
        }
        public RunInProgressException(String loaderName, Throwable cause) {
            super("A run for loader '" + loaderName + "' is already in progress", cause);
        }
    }
}
