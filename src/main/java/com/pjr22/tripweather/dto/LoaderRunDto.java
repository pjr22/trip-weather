package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.LoaderRun;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * One {@code loader_runs} row in the shape served by the admin loaders
 * API. Phase 2 of ADMIN_CONSOLE.md.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoaderRunDto {

    private Long id;
    private String loaderName;
    private String triggerType;
    private ZonedDateTime startedAt;
    private ZonedDateTime finishedAt;
    private String status;
    private Long rowsAffected;
    private String errorMessage;

    public static LoaderRunDto from(LoaderRun run) {
        if (run == null) {
            return null;
        }
        return new LoaderRunDto(
                run.getId(),
                run.getLoaderName(),
                run.getTriggerType() != null ? run.getTriggerType().name() : null,
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getStatus() != null ? run.getStatus().name() : null,
                run.getRowsAffected(),
                run.getErrorMessage());
    }
}
