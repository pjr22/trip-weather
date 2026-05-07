package com.pjr22.tripweather.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Dynamic-filter spatial query against {@code ev_stations}, mirroring the
 * filter parameters NREL accepts on its single-station endpoint so the local
 * mirror behaves identically to a direct upstream call. Only the filters that
 * the caller supplies are appended to the WHERE clause; an absent value means
 * "no filter on that field," matching NREL's omit-defaults behavior.
 */
@Repository
public class EvStationQueryDao {

    /** NREL connector type codes are all uppercase alphanumeric (J1772COMBO,
     *  CHADEMO, NEMA1450, etc.); validating against this pattern lets us pass
     *  them inside a Postgres array literal {@code '{...}'} without escaping
     *  concerns. Anything outside the pattern is rejected up front. */
    private static final Pattern CONNECTOR_TYPE_PATTERN = Pattern.compile("^[A-Z0-9]+$");

    private final NamedParameterJdbcTemplate jdbc;

    public EvStationQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns at most {@code limit} active stations whose location is within
     * {@code radiusMeters} of {@code routeWkt}, filtered by any non-null fields
     * on {@code filter}. Result rows expose only what the response builder
     * needs: id, lat/lon (extracted from the geography), and the upstream
     * properties JSON for the GeoJSON {@code properties} block.
     */
    public List<StationRow> findAlongRoute(String routeWkt,
                                           double radiusMeters,
                                           Filter filter,
                                           int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id,
                       ST_X(location::geometry) AS lon,
                       ST_Y(location::geometry) AS lat,
                       properties::text         AS properties_json
                  FROM ev_stations
                 WHERE active
                   AND ST_DWithin(location, ST_GeographyFromText(:routeWkt), :radiusMeters)
                """);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("routeWkt", routeWkt)
                .addValue("radiusMeters", radiusMeters);

        if (filter.fuelType() != null) {
            sql.append("   AND fuel_type_code = :fuelType\n");
            params.addValue("fuelType", filter.fuelType());
        }
        if (filter.status() != null) {
            sql.append("   AND status_code = :status\n");
            params.addValue("status", filter.status());
        }
        if (filter.access() != null) {
            sql.append("   AND access_code = :access\n");
            params.addValue("access", filter.access());
        }
        if (filter.networks() != null && !filter.networks().isEmpty()) {
            sql.append("   AND ev_network IN (:networks)\n");
            params.addValue("networks", filter.networks());
        }
        if (filter.connectorTypes() != null && !filter.connectorTypes().isEmpty()) {
            // Postgres array overlap is the OR-of-multiple-values semantics
            // NREL applies to a comma-separated ev_connector_type parameter.
            sql.append("   AND ev_connector_types && CAST(:connectorTypes AS text[])\n");
            params.addValue("connectorTypes", toPgTextArrayLiteral(filter.connectorTypes()));
        }
        if (filter.requireDcFast()) {
            sql.append("   AND COALESCE(ev_dc_fast_num, 0) > 0\n");
        }
        if (filter.requireLevel2()) {
            sql.append("   AND COALESCE(ev_level2_evse_num, 0) > 0\n");
        }
        if (filter.requireLevel1()) {
            sql.append("   AND COALESCE(ev_level1_evse_num, 0) > 0\n");
        }

        sql.append(" LIMIT :resultLimit");
        params.addValue("resultLimit", limit);

        return jdbc.query(sql.toString(), params, (rs, i) -> new StationRow(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getString("properties_json")));
    }

    private static String toPgTextArrayLiteral(List<String> values) {
        List<String> validated = new ArrayList<>(values.size());
        for (String v : values) {
            if (v == null || !CONNECTOR_TYPE_PATTERN.matcher(v).matches()) {
                throw new IllegalArgumentException(
                        "Unsupported connector type code: " + v);
            }
            validated.add(v);
        }
        return "{" + String.join(",", validated) + "}";
    }

    /** Filters mirror the NREL parameter set the UI sends today. Null/empty
     *  fields are no-ops. {@code requireXxx} flags translate the
     *  {@code ev_charging_level} parameter to per-level count predicates. */
    public record Filter(String fuelType,
                         String status,
                         String access,
                         List<String> networks,
                         List<String> connectorTypes,
                         boolean requireDcFast,
                         boolean requireLevel2,
                         boolean requireLevel1) {}

    public record StationRow(long id, double latitude, double longitude, String propertiesJson) {}
}
