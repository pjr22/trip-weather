package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * One OSM extract managed by the host-side cron
 * ({@code docker/refreshOrsGraph.sh}). Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>The admin console writes the schedule columns
 * ({@code active}, {@code check_interval_days}, {@code next_check_at},
 * {@code update_interval_days}, {@code next_update_at}, {@code geofabrik_url});
 * the cron writes the state columns ({@code last_check_at},
 * {@code last_remote_*}, {@code last_apply_*}). Both sides read the whole row.
 *
 * <p>Two decoupled schedules:
 * <ul>
 *   <li><b>Check</b> — cheap, ~50 bytes per fetch. Auto-rescheduled by
 *       {@code check_interval_days} after each check (default 7 days).
 *       Manual checks from the admin console fetch the .md5 in the JVM
 *       process and update the state columns but leave
 *       {@code next_check_at} alone.</li>
 *   <li><b>Apply</b> — full download + graph rebuild + container restart.
 *       Admin-driven by default ({@code update_interval_days IS NULL});
 *       the admin sets {@code next_update_at = now()} via the
 *       "Schedule now" action and the cron picks it up on its next tick.
 *       Set {@code update_interval_days} to opt into automatic
 *       reschedule after each successful apply.</li>
 * </ul>
 *
 * <p>{@code last_apply_status} is one of: {@code OK}, {@code NO_CHANGE}
 * (apply ran but md5 matched the deployed one — skipped the heavy work),
 * {@code CHECK_FAILED}, {@code DOWNLOAD_FAILED}, {@code BUILD_FAILED},
 * {@code RESTART_FAILED}.
 */
@Entity
@Table(name = "pbf_files")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PbfFile {

    @Id
    @Column(name = "pbf_name", length = 64)
    private String pbfName;

    @Column(name = "geofabrik_url", nullable = false, columnDefinition = "TEXT")
    private String geofabrikUrl;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    // -- Check schedule -------------------------------------------------------

    @Column(name = "check_interval_days", nullable = false)
    private int checkIntervalDays = 7;

    @Column(name = "next_check_at")
    private ZonedDateTime nextCheckAt;

    // -- Apply schedule -------------------------------------------------------

    @Column(name = "update_interval_days")
    private Integer updateIntervalDays;

    @Column(name = "next_update_at")
    private ZonedDateTime nextUpdateAt;

    // -- Cron / admin-written state -------------------------------------------

    @Column(name = "last_check_at")
    private ZonedDateTime lastCheckAt;

    @Column(name = "last_remote_md5", length = 32)
    private String lastRemoteMd5;

    @Column(name = "last_remote_modified")
    private ZonedDateTime lastRemoteModified;

    @Column(name = "last_apply_started_at")
    private ZonedDateTime lastApplyStartedAt;

    @Column(name = "last_apply_finished_at")
    private ZonedDateTime lastApplyFinishedAt;

    @Column(name = "last_apply_md5", length = 32)
    private String lastApplyMd5;

    @Column(name = "last_apply_status", length = 16)
    private String lastApplyStatus;

    @Column(name = "last_apply_error", columnDefinition = "TEXT")
    private String lastApplyError;
}
