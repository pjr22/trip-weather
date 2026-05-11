package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.Status;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.repository.LoaderRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoaderRunRecorder}. Recorder is the linchpin of
 * Phase 2's loader-runs framework: it owns the start → success/fail
 * lifecycle and the concurrency-guard semantics that map to HTTP 409.
 */
@ExtendWith(MockitoExtension.class)
class LoaderRunRecorderTest {

    @Mock private LoaderRunRepository repository;

    @InjectMocks private LoaderRunRecorder recorder;

    @Test
    void start_insertsRunningRow_whenNoneInFlight() {
        when(repository.existsByLoaderNameAndStatus("ev-stations", Status.RUNNING))
                .thenReturn(false);
        when(repository.saveAndFlush(any(LoaderRun.class)))
                .thenAnswer(inv -> {
                    LoaderRun r = inv.getArgument(0);
                    r.setId(123L);
                    return r;
                });

        ZonedDateTime before = ZonedDateTime.now();
        LoaderRun run = recorder.start("ev-stations", TriggerType.CRON);
        ZonedDateTime after = ZonedDateTime.now();

        assertThat(run.getId()).isEqualTo(123L);
        assertThat(run.getLoaderName()).isEqualTo("ev-stations");
        assertThat(run.getTriggerType()).isEqualTo(TriggerType.CRON);
        assertThat(run.getStatus()).isEqualTo(Status.RUNNING);
        assertThat(run.getStartedAt()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(run.getFinishedAt()).isNull();
    }

    @Test
    void start_throwsConflict_whenRunningRowAlreadyExists() {
        // Pre-check path — the cheaper of the two concurrency guards.
        when(repository.existsByLoaderNameAndStatus("ev-stations", Status.RUNNING))
                .thenReturn(true);

        assertThatThrownBy(() -> recorder.start("ev-stations", TriggerType.MANUAL))
                .isInstanceOf(LoaderRunRecorder.RunInProgressException.class)
                .hasMessageContaining("ev-stations");

        verify(repository, never()).saveAndFlush(any(LoaderRun.class));
    }

    @Test
    void start_translatesUniqueIndexViolation_toConflict() {
        // TOCTOU path — pre-check passes but the partial unique index
        // catches a concurrent insert. Recorder must surface the same
        // RunInProgressException so callers see consistent behaviour.
        when(repository.existsByLoaderNameAndStatus("ev-stations", Status.RUNNING))
                .thenReturn(false);
        when(repository.saveAndFlush(any(LoaderRun.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                      + "\"loader_runs_running_unique_idx\""));

        assertThatThrownBy(() -> recorder.start("ev-stations", TriggerType.MANUAL))
                .isInstanceOf(LoaderRunRecorder.RunInProgressException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void success_marksRunSuccessful_withRowsAffected() {
        LoaderRun run = newRunningRun(7L, "ev-stations", TriggerType.CRON);
        when(repository.findById(7L)).thenReturn(Optional.of(run));
        when(repository.save(any(LoaderRun.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.success(run, 12345L);

        ArgumentCaptor<LoaderRun> savedCaptor = ArgumentCaptor.forClass(LoaderRun.class);
        verify(repository).save(savedCaptor.capture());
        LoaderRun saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(saved.getRowsAffected()).isEqualTo(12345L);
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void success_isNoOp_whenRunIdNoLongerExists() {
        // A late-arriving recorder.success after the row was somehow
        // deleted out from under us shouldn't crash the wrapping
        // loader; logged-and-skipped is fine.
        LoaderRun run = newRunningRun(99L, "ev-stations", TriggerType.CRON);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        recorder.success(run, 0L);   // must not throw

        verify(repository, never()).save(any(LoaderRun.class));
    }

    @Test
    void fail_marksRunFailed_withFormattedErrorMessage() {
        LoaderRun run = newRunningRun(8L, "guest-route-cleanup", TriggerType.MANUAL);
        when(repository.findById(8L)).thenReturn(Optional.of(run));
        when(repository.save(any(LoaderRun.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.fail(run, new IllegalStateException("connection refused"));

        ArgumentCaptor<LoaderRun> savedCaptor = ArgumentCaptor.forClass(LoaderRun.class);
        verify(repository).save(savedCaptor.capture());
        LoaderRun saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(Status.FAIL);
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getRowsAffected()).isNull();
        // Message format is "ExceptionType: message" — keep this stable
        // so the admin UI can rely on the colon-separated layout.
        assertThat(saved.getErrorMessage())
                .startsWith("IllegalStateException:")
                .contains("connection refused");
    }

    @Test
    void fail_truncatesLongErrorMessages() {
        LoaderRun run = newRunningRun(9L, "ev-stations", TriggerType.CRON);
        when(repository.findById(9L)).thenReturn(Optional.of(run));
        when(repository.save(any(LoaderRun.class))).thenAnswer(inv -> inv.getArgument(0));
        // 5000-char message — past the 4000-char cap.
        String huge = "x".repeat(5000);

        recorder.fail(run, new RuntimeException(huge));

        ArgumentCaptor<LoaderRun> savedCaptor = ArgumentCaptor.forClass(LoaderRun.class);
        verify(repository).save(savedCaptor.capture());
        // Length should be exactly the cap; the format prefix
        // "RuntimeException: " takes some chars at the start.
        assertThat(savedCaptor.getValue().getErrorMessage()).hasSize(4000);
    }

    @Test
    void fail_handlesNullThrowable_gracefully() {
        // Defensive: if a caller passes null somehow (shouldn't happen,
        // but log4j-shaped wrappers can drop the exception), don't NPE.
        LoaderRun run = newRunningRun(10L, "ev-stations", TriggerType.CRON);
        when(repository.findById(10L)).thenReturn(Optional.of(run));
        when(repository.save(any(LoaderRun.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.fail(run, null);

        ArgumentCaptor<LoaderRun> savedCaptor = ArgumentCaptor.forClass(LoaderRun.class);
        verify(repository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(Status.FAIL);
        assertThat(savedCaptor.getValue().getErrorMessage()).isNotNull();
    }

    @Test
    void fail_isNoOp_whenRunIdNoLongerExists() {
        LoaderRun run = newRunningRun(11L, "ev-stations", TriggerType.CRON);
        when(repository.findById(11L)).thenReturn(Optional.empty());

        recorder.fail(run, new RuntimeException("something"));   // must not throw

        verify(repository, never()).save(any(LoaderRun.class));
    }

    @Test
    void runInProgressException_messageIncludesLoaderName() {
        // Make sure the exception's message is operator-friendly — the
        // controller surfaces it directly in the 409 response body.
        LoaderRunRecorder.RunInProgressException e =
                new LoaderRunRecorder.RunInProgressException("ors-coverage:colorado");
        assertThat(e.getMessage()).contains("ors-coverage:colorado");
    }

    private LoaderRun newRunningRun(long id, String name, TriggerType trigger) {
        LoaderRun run = new LoaderRun();
        run.setId(id);
        run.setLoaderName(name);
        run.setTriggerType(trigger);
        run.setStatus(Status.RUNNING);
        run.setStartedAt(ZonedDateTime.now());
        return run;
    }
}
