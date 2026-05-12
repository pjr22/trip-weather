package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.AdminUserDeleteResult;
import com.pjr22.tripweather.dto.AdminUserPage;
import com.pjr22.tripweather.dto.AdminUserSummary;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-side user-management operations: paginated list, enable/disable,
 * force-verify, hard-delete. Phase 4 of ADMIN_CONSOLE.md.
 *
 * <p>The list query goes through {@link EntityManager#createNativeQuery} so
 * we can join route counts and pending-verification existence in a single
 * round trip instead of N+1. JPA-path enable/disable goes through
 * {@link UserRepository} normally — the {@code users} table has no
 * {@code @SQLRestriction}, so no bypass dance is needed.
 *
 * <p>Hard-delete relies on database-level {@code ON DELETE CASCADE} on the
 * routes / email_verifications / password_resets foreign keys (set up in
 * {@code dev_scripts/user-accounts-db-migration.sh}); it sweeps soft-deleted
 * routes too, since the FK doesn't honour Hibernate's
 * {@code @SQLRestriction("deleted_at IS NULL")}. Counts are captured
 * pre-delete via a native query so the API can report what got cascaded.
 */
@Service
@Slf4j
public class AdminUserService {

    /**
     * The shared anonymous "guest" user that owns every route saved without
     * a login. Hidden from the admin users view because it's a structural
     * row — it must always exist and stay enabled, so the enable/disable/
     * force-verify/delete actions make no sense for it. Mutation endpoints
     * are not separately guarded (an admin who hand-crafts the guest UUID
     * could still hit them); the filter here is the operator-facing
     * affordance only. Mirrors the {@code GUEST_USER_NAME} constant in
     * {@link AdminRouteService} (which uses it the other way round, as a
     * filter dimension on the Routes view).
     */
    private static final String GUEST_USER_NAME = "guest";

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * Whitelist of column names accepted in the {@code sort} query parameter,
     * mapped to the SQL fragment used in {@code ORDER BY}. Field names match
     * the DTO; SQL fragments reference the {@code u} table alias used in the
     * list query.
     */
    private static final Map<String, String> SORT_COLUMNS;
    static {
        Map<String, String> sorts = new LinkedHashMap<>();
        sorts.put("created", "u.created");
        sorts.put("email", "u.email");
        sorts.put("name", "u.name");
        sorts.put("enabled", "u.enabled");
        SORT_COLUMNS = Map.copyOf(sorts);
    }

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetRepository passwordResetRepository;

    public AdminUserService(UserRepository userRepository,
                            EmailVerificationRepository emailVerificationRepository,
                            PasswordResetRepository passwordResetRepository) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordResetRepository = passwordResetRepository;
    }

    /**
     * Search + filter + paginate users for the admin console.
     *
     * @param q       optional substring; matches user email OR name,
     *                case-insensitive. {@code null} or blank means no filter.
     * @param enabled optional tri-state filter: {@code "true"}, {@code "false"},
     *                or {@code null}/{@code "all"} (no filter).
     * @param page    zero-based page index.
     * @param size    page size; clamped to {@link #MAX_PAGE_SIZE}.
     * @param sort    {@code field[,direction]}; field must be one of
     *                {@link #SORT_COLUMNS} keys. Defaults to {@code created,desc}.
     */
    @Transactional(readOnly = true)
    public AdminUserPage list(String q, String enabled, int page, int size, String sort) {
        int effectiveSize = clampSize(size);
        int effectivePage = Math.max(0, page);

        ParsedSort parsedSort = parseSort(sort);
        ParsedEnabledFilter enabledFilter = parseEnabledFilter(enabled);
        String trimmedQ = (q == null || q.isBlank()) ? null : q.trim();

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        // Always exclude the shared guest user — it's a structural row, not
        // an operator-actionable account (see GUEST_USER_NAME comment).
        where.append(" AND u.name <> :guestName");
        params.put("guestName", GUEST_USER_NAME);

        if (trimmedQ != null) {
            where.append(" AND (LOWER(u.email) LIKE LOWER(:q)"
                       + " OR LOWER(u.name) LIKE LOWER(:q))");
            params.put("q", "%" + trimmedQ + "%");
        }
        if (enabledFilter == ParsedEnabledFilter.ENABLED) {
            where.append(" AND u.enabled = true");
        } else if (enabledFilter == ParsedEnabledFilter.DISABLED) {
            where.append(" AND u.enabled = false");
        }

        // Total count for pagination. Same WHERE, no joins / ORDER / LIMIT.
        String countSql = "SELECT COUNT(*) FROM users u" + where;
        Query countQuery = em.createNativeQuery(countSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new AdminUserPage(List.of(), 0L, 0, effectivePage, effectiveSize);
        }

        // Page of rows. route_count is active-only (matches the DTO contract);
        // has_pending_verification checks for at least one not-yet-consumed
        // and not-yet-expired verification token. Two correlated sub-selects
        // keep the page render to one round trip even for large user tables.
        String listSql = "SELECT u.id, u.email, u.name, u.enabled, u.created,"
                       + " (SELECT COUNT(*) FROM routes r"
                       + "    WHERE r.user_id = u.id AND r.deleted_at IS NULL) AS route_count,"
                       + " EXISTS (SELECT 1 FROM email_verifications ev"
                       + "    WHERE ev.user_id = u.id"
                       + "      AND ev.consumed_at IS NULL"
                       + "      AND ev.expires_at > now()) AS has_pending_verification"
                       + " FROM users u"
                       + where
                       + " ORDER BY " + parsedSort.orderBy()
                       + " LIMIT :limit OFFSET :offset";
        Query listQuery = em.createNativeQuery(listSql);
        params.forEach(listQuery::setParameter);
        listQuery.setParameter("limit", effectiveSize);
        listQuery.setParameter("offset", (long) effectivePage * effectiveSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = listQuery.getResultList();
        List<AdminUserSummary> content = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            String email = (String) row[1];
            String name = (String) row[2];
            boolean userEnabled = (Boolean) row[3];
            LocalDateTime created = toLocalDateTime(row[4]);
            long routeCount = ((Number) row[5]).longValue();
            boolean hasPendingVerification = (Boolean) row[6];
            content.add(new AdminUserSummary(
                    id, email, name, userEnabled, created, routeCount, hasPendingVerification));
        }

        int totalPages = (int) Math.ceil(total / (double) effectiveSize);
        return new AdminUserPage(content, total, totalPages, effectivePage, effectiveSize);
    }

    /**
     * Flip {@code enabled} to a fixed value. Returns a tri-state so the
     * controller can distinguish "no such user" (→ 404) from "already in
     * that state" (→ 204, idempotent success).
     */
    @Transactional
    public SetEnabledOutcome setEnabled(UUID id, boolean target) {
        return userRepository.findById(id).map(user -> {
            if (user.isEnabled() == target) {
                return SetEnabledOutcome.NO_CHANGE;
            }
            user.setEnabled(target);
            userRepository.save(user);
            log.info("Admin {} user {}", target ? "enabled" : "disabled", id);
            return SetEnabledOutcome.UPDATED;
        }).orElse(SetEnabledOutcome.NOT_FOUND);
    }

    /** Outcome of {@link #setEnabled(UUID, boolean)}. */
    public enum SetEnabledOutcome { NOT_FOUND, NO_CHANGE, UPDATED }

    /**
     * Force-verify a user: set {@code enabled=true} (if not already) and
     * mark every still-open email-verification AND password-reset token for
     * this user as consumed, so a stuck signup co-existing with a stale
     * reset can both be unstuck in one call.
     *
     * <p>"Consumed" rather than "deleted": rows stay in the table for audit
     * until the periodic cleanup job sweeps expired entries. Either way the
     * token can't be redeemed after this — consumption is the canonical
     * "no longer valid" signal everywhere else in {@code UserAccountService}.
     *
     * @return true if the user existed, false otherwise (404 path in the
     *         controller). The boolean does not distinguish between "was
     *         already enabled with no pending tokens" and "had work to do" —
     *         the operator's intent is "make this user good", and we want
     *         the call to be idempotent.
     */
    @Transactional
    public boolean forceVerify(UUID id) {
        return userRepository.findById(id).map(user -> {
            LocalDateTime now = LocalDateTime.now();
            int verificationsConsumed =
                    emailVerificationRepository.consumeOpenForUser(user.getId(), now);
            int resetsConsumed =
                    passwordResetRepository.consumeOpenForUser(user.getId(), now);
            if (!user.isEnabled()) {
                user.setEnabled(true);
                userRepository.save(user);
            }
            log.info("Admin force-verified user {} (verifications consumed: {}, resets consumed: {})",
                    id, verificationsConsumed, resetsConsumed);
            return true;
        }).orElse(false);
    }

    /**
     * Hard-delete a user. Counts cascaded routes pre-delete (both active and
     * soft-deleted) so the caller can report what went with the user; then
     * relies on the DB-level {@code ON DELETE CASCADE} FKs on
     * {@code routes.user_id} / {@code email_verifications.user_id} /
     * {@code password_resets.user_id} to sweep dependents.
     *
     * <p>A native COUNT is used instead of the JPA relationship because the
     * {@code @SQLRestriction("deleted_at IS NULL")} on {@code Route} would
     * hide soft-deleted rows from the entity's collection — and those are
     * exactly the rows we want to report on.
     *
     * @return the cascaded route counts, or {@code null} if the user does
     *         not exist (controller maps to 404).
     */
    @Transactional
    public AdminUserDeleteResult delete(UUID id) {
        if (!userRepository.existsById(id)) {
            return null;
        }
        String countSql =
                "SELECT"
              + " SUM(CASE WHEN deleted_at IS NULL     THEN 1 ELSE 0 END) AS active_count,"
              + " SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END) AS soft_deleted_count"
              + " FROM routes WHERE user_id = :userId";
        Object[] row = (Object[]) em.createNativeQuery(countSql)
                .setParameter("userId", id)
                .getSingleResult();
        long active = row[0] == null ? 0L : ((Number) row[0]).longValue();
        long softDeleted = row[1] == null ? 0L : ((Number) row[1]).longValue();

        userRepository.deleteById(id);
        log.info("Admin hard-deleted user {} (cascaded {} active routes, {} soft-deleted routes)",
                id, active, softDeleted);
        return new AdminUserDeleteResult(active, softDeleted);
    }

    private static int clampSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static ParsedSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedSort("u.created DESC, u.id DESC");
        }
        String[] parts = raw.split(",", 2);
        String field = parts[0].trim();
        String column = SORT_COLUMNS.get(field);
        if (column == null) {
            return new ParsedSort("u.created DESC, u.id DESC");
        }
        String direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? "ASC" : "DESC";
        // Stable secondary sort by id keeps page boundaries deterministic
        // when the primary column has duplicate values (e.g. two users
        // created in the same second by a load test).
        return new ParsedSort(column + " " + direction + ", u.id DESC");
    }

    private static ParsedEnabledFilter parseEnabledFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedEnabledFilter.ANY;
        }
        return switch (raw.toLowerCase()) {
            case "true"  -> ParsedEnabledFilter.ENABLED;
            case "false" -> ParsedEnabledFilter.DISABLED;
            default      -> ParsedEnabledFilter.ANY;
        };
    }

    /**
     * PostgreSQL {@code TIMESTAMP WITHOUT TIME ZONE} (the User entity's
     * {@code created} mapping) comes back from a native query as either a
     * {@link Timestamp} or already-typed {@link LocalDateTime}, depending on
     * Hibernate version. Normalise so the DTO field type stays
     * {@code LocalDateTime} consistently.
     */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        throw new IllegalStateException(
                "Unexpected timestamp type from native query: " + value.getClass());
    }

    private record ParsedSort(String orderBy) {}

    private enum ParsedEnabledFilter { ENABLED, DISABLED, ANY }
}
