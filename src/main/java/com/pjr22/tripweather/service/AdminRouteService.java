package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.AdminRoutePage;
import com.pjr22.tripweather.dto.AdminRouteSummary;
import com.pjr22.tripweather.repository.RouteRepository;
import com.pjr22.tripweather.scheduler.GuestRouteCleanupJob;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Admin-side route operations: paginated list (including soft-deleted),
 * soft-delete, restore, and an async trigger for the cleanup job.
 *
 * <p>The list query goes through {@link EntityManager#createNativeQuery} so
 * Hibernate's {@code @SQLRestriction("deleted_at IS NULL")} on
 * {@link com.pjr22.tripweather.model.Route} does not apply — the admin must be
 * able to see and act on soft-deleted rows. JPA paths elsewhere remain
 * automatically filtered. Phase 1 of ADMIN_CONSOLE.md.
 */
@Service
@Slf4j
public class AdminRouteService {

    private static final String GUEST_USER_NAME = "guest";

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * Whitelist of column names accepted in the {@code sort} query parameter,
     * mapped to the SQL fragment used in {@code ORDER BY}. Keeps the sort
     * out of free-form SQL injection territory while still letting the SPA
     * specify a sort field by name.
     */
    private static final Map<String, String> SORT_COLUMNS;
    static {
        Map<String, String> sorts = new LinkedHashMap<>();
        sorts.put("created", "r.created");
        sorts.put("name", "r.name");
        sorts.put("deletedAt", "r.deleted_at");
        sorts.put("ownerEmail", "u.email");
        SORT_COLUMNS = Map.copyOf(sorts);
    }

    @PersistenceContext
    private EntityManager em;

    private final RouteRepository routeRepository;
    private final UserManagementService userManagementService;
    private final GuestRouteCleanupJob cleanupJob;

    public AdminRouteService(RouteRepository routeRepository,
                             UserManagementService userManagementService,
                             GuestRouteCleanupJob cleanupJob) {
        this.routeRepository = routeRepository;
        this.userManagementService = userManagementService;
        this.cleanupJob = cleanupJob;
    }

    /**
     * Search + filter + paginate routes for the admin console.
     *
     * @param q       optional substring; matches route name OR owner email,
     *                case-insensitive. {@code null} or blank means no filter.
     * @param owner   optional owner-kind filter, {@code "USER"} or
     *                {@code "GUEST"}. {@code null} means no filter.
     * @param deleted tri-state visibility filter for {@code deleted_at}:
     *                {@code "false"} (active only), {@code "true"} (deleted
     *                only), or {@code "all"}. Defaults to {@code "false"}.
     * @param page    zero-based page index.
     * @param size    page size; clamped to {@link #MAX_PAGE_SIZE}.
     * @param sort    {@code field[,direction]}; field must be one of
     *                {@link #SORT_COLUMNS} keys. Defaults to {@code created,desc}.
     */
    @Transactional(readOnly = true)
    public AdminRoutePage list(String q, String owner, String deleted,
                               int page, int size, String sort) {
        int effectiveSize = clampSize(size);
        int effectivePage = Math.max(0, page);

        ParsedSort parsedSort = parseSort(sort);
        ParsedDeletedFilter deletedFilter = parseDeletedFilter(deleted);
        ParsedOwnerFilter ownerFilter = parseOwnerFilter(owner);
        String trimmedQ = (q == null || q.isBlank()) ? null : q.trim();

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (trimmedQ != null) {
            where.append(" AND (LOWER(r.name) LIKE LOWER(:q)"
                       + " OR LOWER(u.email) LIKE LOWER(:q))");
            params.put("q", "%" + trimmedQ + "%");
        }
        if (ownerFilter == ParsedOwnerFilter.GUEST) {
            where.append(" AND u.name = :guestName");
            params.put("guestName", GUEST_USER_NAME);
        } else if (ownerFilter == ParsedOwnerFilter.USER) {
            where.append(" AND u.name <> :guestName");
            params.put("guestName", GUEST_USER_NAME);
        }
        if (deletedFilter == ParsedDeletedFilter.ACTIVE_ONLY) {
            where.append(" AND r.deleted_at IS NULL");
        } else if (deletedFilter == ParsedDeletedFilter.DELETED_ONLY) {
            where.append(" AND r.deleted_at IS NOT NULL");
        }

        // Total count for pagination metadata. Same WHERE, no ORDER / LIMIT.
        String countSql = "SELECT COUNT(*) FROM routes r"
                        + " JOIN users u ON r.user_id = u.id"
                        + where;
        Query countQuery = em.createNativeQuery(countSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new AdminRoutePage(List.of(), 0L, 0, effectivePage, effectiveSize);
        }

        // Page of rows. waypoint_count is computed inline so the page render
        // is one round trip instead of N+1.
        String listSql = "SELECT r.id, r.name, r.created, r.deleted_at,"
                       + " u.email AS owner_email, u.name AS owner_name,"
                       + " (SELECT COUNT(*) FROM waypoints w WHERE w.route_id = r.id) AS waypoint_count"
                       + " FROM routes r"
                       + " JOIN users u ON r.user_id = u.id"
                       + where
                       + " ORDER BY " + parsedSort.orderBy()
                       + " LIMIT :limit OFFSET :offset";
        Query listQuery = em.createNativeQuery(listSql);
        params.forEach(listQuery::setParameter);
        listQuery.setParameter("limit", effectiveSize);
        listQuery.setParameter("offset", (long) effectivePage * effectiveSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = listQuery.getResultList();
        List<AdminRouteSummary> content = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            ZonedDateTime created = toZonedDateTime(row[2]);
            ZonedDateTime deletedAt = toZonedDateTime(row[3]);
            String ownerEmail = (String) row[4];
            String ownerName = (String) row[5];
            long waypointCount = ((Number) row[6]).longValue();
            String ownerKind = GUEST_USER_NAME.equals(ownerName) ? "GUEST" : "USER";
            content.add(new AdminRouteSummary(
                    id, name, ownerEmail, ownerKind, waypointCount, created, deletedAt));
        }

        int totalPages = (int) Math.ceil(total / (double) effectiveSize);
        return new AdminRoutePage(content, total, totalPages, effectivePage, effectiveSize);
    }

    /**
     * Soft-delete a single route. Returns true iff a row was newly marked
     * deleted (already-deleted or non-existent ids return false so the
     * controller can map to 404).
     */
    @Transactional
    public boolean softDelete(UUID id) {
        int updated = routeRepository.adminSoftDelete(id, ZonedDateTime.now());
        if (updated > 0) {
            log.info("Admin soft-deleted route {}", id);
            return true;
        }
        return false;
    }

    /**
     * Restore a soft-deleted route. Returns true iff a row was actually
     * restored (so the controller can return 404 for ids that are missing
     * or weren't soft-deleted in the first place).
     */
    @Transactional
    public boolean restore(UUID id) {
        int updated = routeRepository.adminRestore(id);
        if (updated > 0) {
            log.info("Admin restored route {}", id);
            return true;
        }
        return false;
    }

    /**
     * Fire the cleanup job's two-stage purge on a background thread so the
     * admin endpoint can return 202 immediately. Logs the outcome; Phase 2
     * will replace this with a {@code loader_runs} row + run id surfaced in
     * the response.
     */
    public void triggerCleanupAsync() {
        log.info("Admin triggered cleanup (async)");
        CompletableFuture.runAsync(() -> {
            try {
                cleanupJob.cleanGuestRoutes();
            } catch (Exception e) {
                log.error("Admin-triggered cleanup failed", e);
            }
        });
    }

    private static int clampSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static ParsedSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedSort("r.created DESC, r.id DESC");
        }
        String[] parts = raw.split(",", 2);
        String field = parts[0].trim();
        String column = SORT_COLUMNS.get(field);
        if (column == null) {
            // Unknown field — fall back to default rather than 500.
            return new ParsedSort("r.created DESC, r.id DESC");
        }
        String direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? "ASC" : "DESC";
        // Stable secondary sort by id keeps page boundaries deterministic
        // when the primary column has duplicate values (e.g. two routes
        // soft-deleted in the same millisecond).
        return new ParsedSort(column + " " + direction + ", r.id DESC");
    }

    private static ParsedDeletedFilter parseDeletedFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedDeletedFilter.ACTIVE_ONLY;
        }
        return switch (raw.toLowerCase()) {
            case "true" -> ParsedDeletedFilter.DELETED_ONLY;
            case "all"  -> ParsedDeletedFilter.ALL;
            default     -> ParsedDeletedFilter.ACTIVE_ONLY;
        };
    }

    private static ParsedOwnerFilter parseOwnerFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedOwnerFilter.ANY;
        }
        return switch (raw.toUpperCase()) {
            case "USER"  -> ParsedOwnerFilter.USER;
            case "GUEST" -> ParsedOwnerFilter.GUEST;
            default      -> ParsedOwnerFilter.ANY;
        };
    }

    /**
     * PostgreSQL {@code TIMESTAMPTZ} columns come back from a native query as
     * one of several Java time types depending on driver / Hibernate version:
     * {@link java.time.Instant} (modern PG JDBC + Hibernate 6 — the path
     * actually exercised here), {@link java.time.OffsetDateTime} (older
     * Hibernate path), {@link Timestamp} (very old driver), or already
     * {@link ZonedDateTime} (defensive). Normalise all of them to
     * {@link ZonedDateTime} (matching the entity's {@code created} field) so
     * the API shape stays consistent regardless of the underlying mapping.
     */
    private static ZonedDateTime toZonedDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ZonedDateTime z) {
            return z;
        }
        if (value instanceof java.time.Instant inst) {
            return inst.atZone(ZoneOffset.UTC);
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toZonedDateTime();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atZone(ZoneOffset.UTC);
        }
        throw new IllegalStateException(
                "Unexpected timestamp type from native query: " + value.getClass());
    }

    private record ParsedSort(String orderBy) {}

    private enum ParsedDeletedFilter { ACTIVE_ONLY, DELETED_ONLY, ALL }

    private enum ParsedOwnerFilter { USER, GUEST, ANY }
}
