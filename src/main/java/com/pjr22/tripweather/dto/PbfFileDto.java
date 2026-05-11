package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.PbfFile;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * Wire shape for {@code GET /api/admin/pbfs} and the per-row endpoints.
 * Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>Three derived flags are computed server-side so the admin SPA renders
 * them without having to reproduce the staleness / in-flight logic:
 * <ul>
 *   <li>{@code stale} — {@code last_remote_md5} is non-null and differs
 *       from {@code last_apply_md5}. Upstream has a newer pbf than what's
 *       deployed.</li>
 *   <li>{@code applyInFlight} — {@code last_apply_started_at} is non-null
 *       and {@code last_apply_finished_at} is null.</li>
 *   <li>{@code applyStuck} — {@code applyInFlight} AND
 *       {@code last_apply_started_at} is older than the 4 h
 *       stale-detection window. UI shows "Retry stuck apply" button when
 *       this flag is true.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PbfFileDto {

    /** Width of the stale-apply detection window. Must match
     *  {@code APPLY_STALE_AFTER} in
     *  {@code docker/refreshOrsGraph.sh}. */
    private static final long APPLY_STUCK_AFTER_HOURS = 4;

    private String pbfName;
    private String geofabrikUrl;
    private boolean active;

    private int checkIntervalDays;
    private ZonedDateTime nextCheckAt;

    private Integer updateIntervalDays;
    private ZonedDateTime nextUpdateAt;

    private ZonedDateTime lastCheckAt;
    private String lastRemoteMd5;
    private ZonedDateTime lastRemoteModified;

    private ZonedDateTime lastApplyStartedAt;
    private ZonedDateTime lastApplyFinishedAt;
    private String lastApplyMd5;
    private String lastApplyStatus;
    private String lastApplyError;

    // Derived flags.
    private boolean stale;
    private boolean applyInFlight;
    private boolean applyStuck;

    public static PbfFileDto from(PbfFile entity) {
        if (entity == null) return null;
        PbfFileDto dto = new PbfFileDto();
        dto.pbfName = entity.getPbfName();
        dto.geofabrikUrl = entity.getGeofabrikUrl();
        dto.active = entity.isActive();
        dto.checkIntervalDays = entity.getCheckIntervalDays();
        dto.nextCheckAt = entity.getNextCheckAt();
        dto.updateIntervalDays = entity.getUpdateIntervalDays();
        dto.nextUpdateAt = entity.getNextUpdateAt();
        dto.lastCheckAt = entity.getLastCheckAt();
        dto.lastRemoteMd5 = entity.getLastRemoteMd5();
        dto.lastRemoteModified = entity.getLastRemoteModified();
        dto.lastApplyStartedAt = entity.getLastApplyStartedAt();
        dto.lastApplyFinishedAt = entity.getLastApplyFinishedAt();
        dto.lastApplyMd5 = entity.getLastApplyMd5();
        dto.lastApplyStatus = entity.getLastApplyStatus();
        dto.lastApplyError = entity.getLastApplyError();

        dto.stale = entity.getLastRemoteMd5() != null
                && !entity.getLastRemoteMd5().equals(entity.getLastApplyMd5());
        dto.applyInFlight = entity.getLastApplyStartedAt() != null
                && entity.getLastApplyFinishedAt() == null;
        dto.applyStuck = dto.applyInFlight
                && entity.getLastApplyStartedAt()
                        .isBefore(ZonedDateTime.now().minusHours(APPLY_STUCK_AFTER_HOURS));
        return dto;
    }
}
