package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.AdminFavoritePage;
import com.pjr22.tripweather.dto.AdminFavoriteSummary;
import com.pjr22.tripweather.repository.FavoriteWaypointRepository;
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

/**
 * Admin-side favorite-waypoint operations: paginated list (including soft-
 * deleted), soft-delete, restore, and hard-delete. Phase 5 of
 * FAVORITES_AND_ROUTE_MGMT.md — mirrors {@link AdminRouteService} so the
 * two admin views share the same shape of query / filter / paginate logic.
 *
 * <p>The list query goes through {@link EntityManager#createNativeQuery} so
 * Hibernate's {@code @SQLRestriction("deleted_at IS NULL")} on
 * {@link com.pjr22.tripweather.model.FavoriteWaypoint} does not apply — the
 * admin must be able to see and act on soft-deleted rows. JPA paths
 * elsewhere remain automatically filtered.
 */
@Service
@Slf4j
public class AdminFavoriteService {

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
        sorts.put("created", "f.created");
        sorts.put("label", "f.label");
        sorts.put("deletedAt", "f.deleted_at");
        sorts.put("ownerEmail", "u.email");
        SORT_COLUMNS = Map.copyOf(sorts);
    }

    @PersistenceContext
    private EntityManager em;

    private final FavoriteWaypointRepository favoriteRepository;

    public AdminFavoriteService(FavoriteWaypointRepository favoriteRepository) {
        this.favoriteRepository = favoriteRepository;
    }

    /**
     * Search + filter + paginate favorites for the admin console.
     *
     * @param q       optional substring; matches favorite label OR owner
     *                email, case-insensitive. {@code null} or blank means
     *                no filter.
     * @param deleted tri-state visibility filter for {@code deleted_at}:
     *                {@code "false"} (active only), {@code "true"} (deleted
     *                only), or {@code "all"}. Defaults to {@code "false"}.
     * @param page    zero-based page index.
     * @param size    page size; clamped to {@link #MAX_PAGE_SIZE}.
     * @param sort    {@code field[,direction]}; field must be one of
     *                {@link #SORT_COLUMNS} keys. Defaults to
     *                {@code created,desc}.
     */
    @Transactional(readOnly = true)
    public AdminFavoritePage list(String q, String deleted,
                                  int page, int size, String sort) {
        int effectiveSize = clampSize(size);
        int effectivePage = Math.max(0, page);

        ParsedSort parsedSort = parseSort(sort);
        ParsedDeletedFilter deletedFilter = parseDeletedFilter(deleted);
        String trimmedQ = (q == null || q.isBlank()) ? null : q.trim();

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (trimmedQ != null) {
            // Substring on label OR owner email — same UX as the routes
            // search, which lets the admin find a row by "the label the user
            // gave it" or "whose row is this".
            where.append(" AND (LOWER(f.label) LIKE LOWER(:q)"
                       + " OR LOWER(u.email) LIKE LOWER(:q))");
            params.put("q", "%" + trimmedQ + "%");
        }
        if (deletedFilter == ParsedDeletedFilter.ACTIVE_ONLY) {
            where.append(" AND f.deleted_at IS NULL");
        } else if (deletedFilter == ParsedDeletedFilter.DELETED_ONLY) {
            where.append(" AND f.deleted_at IS NOT NULL");
        }

        String countSql = "SELECT COUNT(*) FROM favorite_waypoints f"
                        + " JOIN users u ON f.user_id = u.id"
                        + where;
        Query countQuery = em.createNativeQuery(countSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new AdminFavoritePage(List.of(), 0L, 0, effectivePage, effectiveSize);
        }

        String listSql = "SELECT f.id, f.label, f.location_name, f.latitude, f.longitude,"
                       + " f.created, f.deleted_at, u.email AS owner_email"
                       + " FROM favorite_waypoints f"
                       + " JOIN users u ON f.user_id = u.id"
                       + where
                       + " ORDER BY " + parsedSort.orderBy()
                       + " LIMIT :limit OFFSET :offset";
        Query listQuery = em.createNativeQuery(listSql);
        params.forEach(listQuery::setParameter);
        listQuery.setParameter("limit", effectiveSize);
        listQuery.setParameter("offset", (long) effectivePage * effectiveSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = listQuery.getResultList();
        List<AdminFavoriteSummary> content = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            String label = (String) row[1];
            String locationName = (String) row[2];
            Double latitude = ((Number) row[3]).doubleValue();
            Double longitude = ((Number) row[4]).doubleValue();
            ZonedDateTime created = toZonedDateTime(row[5]);
            ZonedDateTime deletedAt = toZonedDateTime(row[6]);
            String ownerEmail = (String) row[7];
            content.add(new AdminFavoriteSummary(
                    id, label, locationName, latitude, longitude,
                    ownerEmail, created, deletedAt));
        }

        int totalPages = (int) Math.ceil(total / (double) effectiveSize);
        return new AdminFavoritePage(content, total, totalPages, effectivePage, effectiveSize);
    }

    /**
     * Soft-delete a single favorite. Returns true iff a row was newly marked
     * deleted (already-deleted or non-existent ids return false so the
     * controller can map to 404).
     */
    @Transactional
    public boolean softDelete(UUID id) {
        int updated = favoriteRepository.adminSoftDelete(id, ZonedDateTime.now());
        if (updated > 0) {
            log.info("Admin soft-deleted favorite {}", id);
            return true;
        }
        return false;
    }

    /**
     * Restore a soft-deleted favorite. Returns true iff a row was actually
     * restored (so the controller can return 404 for ids that are missing
     * or weren't soft-deleted in the first place).
     */
    @Transactional
    public boolean restore(UUID id) {
        int updated = favoriteRepository.adminRestore(id);
        if (updated > 0) {
            log.info("Admin restored favorite {}", id);
            return true;
        }
        return false;
    }

    /**
     * Hard-delete (purge) a single favorite. Bypasses the soft-delete safety
     * net entirely — used by the admin "Purge" action on already-soft-deleted
     * rows that the operator doesn't want to wait for the grace-window cron
     * to clear. Returns true iff a row was deleted.
     */
    @Transactional
    public boolean hardDelete(UUID id) {
        int deleted = favoriteRepository.adminHardDelete(id);
        if (deleted > 0) {
            log.info("Admin hard-deleted (purged) favorite {}", id);
            return true;
        }
        return false;
    }

    private static int clampSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static ParsedSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedSort("f.created DESC, f.id DESC");
        }
        String[] parts = raw.split(",", 2);
        String field = parts[0].trim();
        String column = SORT_COLUMNS.get(field);
        if (column == null) {
            return new ParsedSort("f.created DESC, f.id DESC");
        }
        String direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? "ASC" : "DESC";
        return new ParsedSort(column + " " + direction + ", f.id DESC");
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

    /**
     * Normalise the assorted PG / Hibernate timestamp types to
     * {@link ZonedDateTime}. Same helper as in {@link AdminRouteService} —
     * kept private here rather than promoted to a shared utility because the
     * two admin services are the only callers and the duplication is small.
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
}
