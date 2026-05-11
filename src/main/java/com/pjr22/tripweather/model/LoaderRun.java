package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * One attempt of a scheduled or manually-triggered loader / cleanup job.
 *
 * <p>Three trigger types feed into this table:
 * <ul>
 *   <li>{@link TriggerType#CRON} — fired by Spring's {@code @Scheduled}, or
 *       by the production docker cron's
 *       {@code POST /api/admin/refresh-coverage/{region}}.</li>
 *   <li>{@link TriggerType#MANUAL} — fired by the admin console's "Trigger"
 *       buttons.</li>
 *   <li>{@link TriggerType#BOOTSTRAP} — first-time seed, e.g.
 *       {@link com.pjr22.tripweather.service.EvStationLoader}'s
 *       on-empty-table bootstrap or
 *       {@link com.pjr22.tripweather.routing.GeofabrikCoverageLoader}'s
 *       startup seed of regions with no row yet.</li>
 * </ul>
 *
 * <p>Lifecycle: row is inserted with {@code status=RUNNING} when the work
 * begins and updated to {@code SUCCESS} or {@code FAIL} when it ends. The
 * partial unique index on {@code (loader_name) WHERE status='RUNNING'}
 * (declared in {@code dev_scripts/admin-console-db-migration.sh}) enforces
 * that at most one run per loader is in flight at a time. Phase 2 of
 * ADMIN_CONSOLE.md.
 */
@Entity
@Table(name = "loader_runs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoaderRun {

    /** Cron / manual / bootstrap classification. Stored as a VARCHAR via @Enumerated(STRING). */
    public enum TriggerType { CRON, MANUAL, BOOTSTRAP }

    /** RUNNING for in-flight, SUCCESS / FAIL once finished. */
    public enum Status { RUNNING, SUCCESS, FAIL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loader_name", nullable = false, length = 64)
    private String loaderName;

    @Column(name = "trigger_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TriggerType triggerType;

    @Column(name = "started_at", nullable = false)
    private ZonedDateTime startedAt;

    @Column(name = "finished_at")
    private ZonedDateTime finishedAt;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "rows_affected")
    private Long rowsAffected;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
