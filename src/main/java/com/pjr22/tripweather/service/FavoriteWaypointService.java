package com.pjr22.tripweather.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.pjr22.tripweather.dto.CreateFavoriteRequest;
import com.pjr22.tripweather.dto.FavoriteWaypointDto;
import com.pjr22.tripweather.dto.RenameFavoriteRequest;
import com.pjr22.tripweather.model.FavoriteWaypoint;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.FavoriteWaypointRepository;
import com.pjr22.tripweather.security.CurrentUserService;

/**
 * Service for handling favorite-waypoint CRUD. Phase 1 of
 * FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>Every public method resolves the user via {@link CurrentUserService#currentUser()}
 * (not {@code currentUserOrGuest()} — favorites are an account feature with
 * no guest fallback) and rejects requests for ids not owned by that user
 * with a 404. Mirrors the leak-nothing posture of
 * {@link RoutePersistenceService#deleteRoute(UUID)} — a 403 would tell the
 * caller "that favorite exists but isn't yours", which the API deliberately
 * does not reveal.
 *
 * <p>Anonymous callers are already blocked by SecurityConfig
 * ({@code /api/favorites/** → authenticated}); the {@code currentUser()}
 * empty check here is belt-and-braces against a future config drift.
 */
@Service
public class FavoriteWaypointService {

    private static final Logger logger = LoggerFactory.getLogger(FavoriteWaypointService.class);

    /** Server-side label cap. Matches {@code favorite_waypoints.label VARCHAR(255)}. */
    private static final int LABEL_MAX_LENGTH = 255;

    /** Server-side locationName cap. Matches {@code favorite_waypoints.location_name VARCHAR(1023)}. */
    private static final int LOCATION_NAME_MAX_LENGTH = 1023;

    /**
     * Tier 1 radius: any favorite within this distance counts as a match
     * regardless of locationName. Sized to absorb GPS jitter on a re-acquired
     * current-location read (typical jitter 3-10 m).
     */
    static final double TIER1_RADIUS_METERS = 10.0;

    /**
     * Tier 2 radius: within this distance, a match is only declared when the
     * locationName also matches (case-insensitive, trimmed). Catches the
     * "same address, slightly different coords" case the reverse-geocoder
     * canonicalises, without false-positive matches across unrelated places
     * within a 50 m radius.
     */
    static final double TIER2_RADIUS_METERS = 50.0;

    private final FavoriteWaypointRepository favoriteRepository;
    private final CurrentUserService currentUserService;

    public FavoriteWaypointService(FavoriteWaypointRepository favoriteRepository,
                                   CurrentUserService currentUserService) {
        this.favoriteRepository = favoriteRepository;
        this.currentUserService = currentUserService;
    }

    /**
     * List the current user's favorites. {@code searchOrNull} is treated as
     * blank → full alphabetical list; non-blank → label-or-locationName
     * case-insensitive substring filter.
     */
    @Transactional(readOnly = true)
    public List<FavoriteWaypointDto> listForCurrentUser(String searchOrNull) {
        User user = requireCurrentUser();
        List<FavoriteWaypoint> rows = (searchOrNull == null || searchOrNull.isBlank())
                ? favoriteRepository.findAllByUser(user.getId())
                : favoriteRepository.searchByUser(user.getId(), searchOrNull.trim());
        return rows.stream().map(FavoriteWaypointService::toDto).toList();
    }

    /**
     * Existence check used by the popup heart-toggle's initial state. Returns
     * the best tiered-proximity match (see {@link #matchByProximity}). Empty
     * means "this place isn't starred for the current user" — the controller
     * maps that to a 204 so the client can distinguish it from a 404 / 401.
     */
    @Transactional(readOnly = true)
    public Optional<FavoriteWaypointDto> findAt(double latitude, double longitude, String locationName) {
        User user = requireCurrentUser();
        List<FavoriteWaypoint> candidates = favoriteRepository.findAllByUser(user.getId());
        return matchByProximity(candidates, latitude, longitude, locationName)
                .map(FavoriteWaypointService::toDto);
    }

    /**
     * Create a new favorite owned by the current user. Throws
     * {@link DuplicateFavoriteLabelException} (→ 409) if the label collides
     * with an existing favorite for the same user (case-insensitive).
     */
    @Transactional
    public FavoriteWaypointDto create(CreateFavoriteRequest req) {
        User user = requireCurrentUser();

        String label = requireNonBlank(req.label(), "label");
        if (label.length() > LABEL_MAX_LENGTH) {
            throw new InvalidFavoriteException(
                    "label is too long (max " + LABEL_MAX_LENGTH + " characters)");
        }

        Double latitude = req.latitude();
        Double longitude = req.longitude();
        if (latitude == null || longitude == null) {
            throw new InvalidFavoriteException("latitude and longitude are required");
        }

        String locationName = normalizeLocationName(req.locationName(), latitude, longitude);

        if (favoriteRepository.existsByUserIdAndLabelIgnoreCase(user.getId(), label)) {
            throw new DuplicateFavoriteLabelException(
                    "You already have a favorite named \"" + label + "\".");
        }

        FavoriteWaypoint favorite = new FavoriteWaypoint();
        favorite.setUser(user);
        favorite.setLabel(label);
        favorite.setLocationName(locationName);
        favorite.setLatitude(latitude);
        favorite.setLongitude(longitude);
        favorite.setElevation(req.elevation());
        // Timezone fields are pass-through: if the caller has them, save
        // them; null is fine. Blank-to-null so an empty-string client send
        // doesn't pollute the column.
        favorite.setTimezoneName(blankToNull(req.timezoneName()));
        favorite.setTimezoneStdOffset(blankToNull(req.timezoneStdOffset()));
        favorite.setTimezoneDstOffset(blankToNull(req.timezoneDstOffset()));
        favorite.setTimezoneStdAbbr(blankToNull(req.timezoneStdAbbr()));
        favorite.setTimezoneDstAbbr(blankToNull(req.timezoneDstAbbr()));
        // id + created set by @PrePersist

        FavoriteWaypoint saved = favoriteRepository.save(favorite);
        logger.info("Favorite {} created for user {}", saved.getId(), user.getId());
        return toDto(saved);
    }

    /**
     * Rename the label of a favorite owned by the current user.
     * {@code locationName} / {@code latitude} / {@code longitude} /
     * {@code elevation} are immutable — a different place is a different
     * favorite.
     */
    @Transactional
    public FavoriteWaypointDto rename(UUID id, RenameFavoriteRequest req) {
        User user = requireCurrentUser();

        String newLabel = requireNonBlank(req.label(), "label");
        if (newLabel.length() > LABEL_MAX_LENGTH) {
            throw new InvalidFavoriteException(
                    "label is too long (max " + LABEL_MAX_LENGTH + " characters)");
        }

        FavoriteWaypoint favorite = favoriteRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new FavoriteNotFoundException("Favorite not found"));

        if (!newLabel.equalsIgnoreCase(favorite.getLabel())
                && favoriteRepository.existsByUserIdAndLabelIgnoreCase(user.getId(), newLabel)) {
            throw new DuplicateFavoriteLabelException(
                    "You already have a favorite named \"" + newLabel + "\".");
        }

        favorite.setLabel(newLabel);
        FavoriteWaypoint saved = favoriteRepository.save(favorite);
        logger.info("Favorite {} renamed by user {}", saved.getId(), user.getId());
        return toDto(saved);
    }

    /**
     * Soft-delete a favorite owned by the current user. The
     * {@link com.pjr22.tripweather.model.FavoriteWaypoint @SQLRestriction}
     * hides the row from every subsequent read; Phase 5's cleanup cron will
     * hard-delete past the grace window.
     */
    @Transactional
    public void softDelete(UUID id) {
        User user = requireCurrentUser();

        FavoriteWaypoint favorite = favoriteRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new FavoriteNotFoundException("Favorite not found"));

        favorite.setDeletedAt(java.time.ZonedDateTime.now());
        favoriteRepository.save(favorite);
        logger.info("Favorite {} soft-deleted by user {}", favorite.getId(), user.getId());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private User requireCurrentUser() {
        return currentUserService.currentUser()
                .orElseThrow(() -> new FavoriteNotFoundException("Favorite not found"));
    }

    private static String requireNonBlank(String s, String field) {
        if (s == null || s.isBlank()) {
            throw new InvalidFavoriteException(field + " is required");
        }
        return s.trim();
    }

    /**
     * The column is {@code NOT NULL} but the user may favorite a place whose
     * reverse-geocode hasn't resolved yet. Fall back to a {@code "lat, lon"}
     * coordinate string so the favorite always carries something searchable.
     * Also enforces the 1023-char column cap.
     */
    private static String normalizeLocationName(String raw, double latitude, double longitude) {
        String name = (raw == null) ? "" : raw.trim();
        if (name.isEmpty()) {
            // 5 decimals = ~1.1 m precision at the equator — enough to
            // distinguish individual building entrances and never collide
            // with "same address, different unit" cases. Locale.US so "."
            // stays as the decimal separator regardless of the JVM's default
            // locale — the SPA parses the string back as a numeric pair in
            // a downstream feature.
            name = String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
        }
        if (name.length() > LOCATION_NAME_MAX_LENGTH) {
            name = name.substring(0, LOCATION_NAME_MAX_LENGTH);
        }
        return name;
    }

    private static FavoriteWaypointDto toDto(FavoriteWaypoint f) {
        return new FavoriteWaypointDto(
                f.getId(),
                f.getLabel(),
                f.getLocationName(),
                f.getLatitude(),
                f.getLongitude(),
                f.getElevation(),
                f.getTimezoneName(),
                f.getTimezoneStdOffset(),
                f.getTimezoneDstOffset(),
                f.getTimezoneStdAbbr(),
                f.getTimezoneDstAbbr(),
                f.getCreated());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Tiered proximity match used by both the heart-toggle /check endpoint
     * and the route-load favoriteId population. Two stages, plus a no-match:
     *
     * <ol>
     *   <li><b>Within 10 m</b> → match regardless of locationName. Absorbs
     *       GPS jitter when the same physical place is re-acquired from a
     *       current-location read.</li>
     *   <li><b>Within 50 m AND same locationName</b> (case-insensitive,
     *       trimmed) → match. Catches the "same address canonicalised by the
     *       reverse-geocoder, slightly different coords" case without
     *       false-positive matches across unrelated places that happen to
     *       be within 50 m.</li>
     *   <li>Otherwise → no match.</li>
     * </ol>
     *
     * <p>Among multiple candidates, tier 1 always beats tier 2; within the
     * same tier, the closest wins.
     *
     * <p>Pure function — no DB access, no user resolution. The caller is
     * responsible for passing only the viewer's own favorites.
     */
    public static Optional<FavoriteWaypoint> matchByProximity(
            List<FavoriteWaypoint> candidates,
            double latitude, double longitude, String locationName) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String normalizedQueryName = normalizeForNameCompare(locationName);

        FavoriteWaypoint best = null;
        double bestDistance = Double.MAX_VALUE;
        int bestTier = Integer.MAX_VALUE;

        for (FavoriteWaypoint f : candidates) {
            if (f.getLatitude() == null || f.getLongitude() == null) continue;
            double d = haversineMeters(latitude, longitude, f.getLatitude(), f.getLongitude());

            int tier;
            if (d <= TIER1_RADIUS_METERS) {
                tier = 1;
            } else if (d <= TIER2_RADIUS_METERS
                    && normalizedQueryName.equals(normalizeForNameCompare(f.getLocationName()))) {
                tier = 2;
            } else {
                continue;
            }

            if (tier < bestTier || (tier == bestTier && d < bestDistance)) {
                best = f;
                bestDistance = d;
                bestTier = tier;
            }
        }
        return Optional.ofNullable(best);
    }

    private static String normalizeForNameCompare(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Great-circle distance in meters between two WGS84 points. Mean-radius
     * approximation; accuracy is ±0.5 % over distances that matter for this
     * use case (a few hundred meters at most), well inside the 10 m / 50 m
     * tier thresholds.
     */
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusMeters = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinDLat2 = Math.sin(dLat / 2);
        double sinDLon2 = Math.sin(dLon / 2);
        double a = sinDLat2 * sinDLat2
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * sinDLon2 * sinDLon2;
        return 2 * earthRadiusMeters * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ------------------------------------------------------------------------
    // Exceptions — same shape as the nested classes in RoutePersistenceService.
    // ------------------------------------------------------------------------

    /**
     * 404 — the favorite doesn't exist for the current user (either truly
     * absent, or owned by someone else). The two cases share a status so
     * the response can't be used to enumerate other users' favorites.
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class FavoriteNotFoundException extends RuntimeException {
        public FavoriteNotFoundException(String message) { super(message); }
    }

    /**
     * 409 — the user already owns a favorite with this label
     * (case-insensitive). Surfaced to the SPA so the duplicate-label
     * prompt can stay open with an inline error.
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    public static class DuplicateFavoriteLabelException extends RuntimeException {
        public DuplicateFavoriteLabelException(String message) { super(message); }
    }

    /**
     * 400 — the request body is missing a required field or violates a
     * length cap. Spring maps this through {@code @ResponseStatus}; the
     * message is returned as the body so the client can surface it.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidFavoriteException extends RuntimeException {
        public InvalidFavoriteException(String message) { super(message); }
    }
}
