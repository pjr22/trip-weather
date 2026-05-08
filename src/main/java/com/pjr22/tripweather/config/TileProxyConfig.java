package com.pjr22.tripweather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the user-facing base URLs for the three Phase 4 tile/icon flows
 * (OSM tiles, NDFD WMS, weather.gov forecast icons) from a single pair of
 * properties:
 *
 * <pre>
 *   trip.tile.proxy-enabled    boolean, default false
 *   trip.tile.proxy-base-url   string,  default empty
 * </pre>
 *
 * The three operating modes:
 * <ol>
 *   <li><b>disabled</b> (dev default, also a runtime fallback when nginx is
 *       unreachable): the frontend, the icon-URL rewriter, and the CSP all
 *       point at upstream public services. {@code ./gradlew bootRun} alone
 *       just works.</li>
 *   <li><b>enabled with empty base</b> (production behind haproxy): relative
 *       paths {@code /tiles/osm}, {@code /tiles/ndfd-wms}, {@code /wx-icons}
 *       resolve through haproxy → nginx. CSP tightens to {@code 'self'}.</li>
 *   <li><b>enabled with absolute base</b> (dev with the nginx sidecar
 *       running on a different host port): absolute URLs prefixed with that
 *       base. CSP whitelists the base origin in addition to {@code 'self'}.</li>
 * </ol>
 *
 * Three consumers read from this bean: {@link com.pjr22.tripweather.controller.TileConfigController}
 * (serves {@code /api/config/tiles} for the frontend), the WeatherService
 * icon-URL rewriter, and {@link SecurityHeadersFilter} (CSP {@code img-src}).
 */
@Component
public class TileProxyConfig {

    private static final String OSM_UPSTREAM_BASE   = "https://{s}.tile.openstreetmap.org";
    private static final String NDFD_UPSTREAM_BASE  = "https://digital.weather.gov/ndfd/wms";
    private static final String ICONS_UPSTREAM_BASE = "https://api.weather.gov";

    private static final String OSM_PROXY_PATH   = "/tiles/osm";
    private static final String NDFD_PROXY_PATH  = "/tiles/ndfd-wms";
    private static final String ICONS_PROXY_PATH = "/wx-icons";

    private final boolean enabled;
    private final String baseUrl;

    public TileProxyConfig(
            @Value("${trip.tile.proxy-enabled:false}") boolean enabled,
            @Value("${trip.tile.proxy-base-url:}") String baseUrl) {
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Empty string when the proxy is enabled with relative paths (production
     *  case behind haproxy); otherwise the configured absolute base. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** Frontend uses this with the OSM XYZ template
     *  ({@code {z}/{x}/{y}.png}); the {@code {s}} subdomain placeholder is
     *  preserved only in upstream-direct mode. */
    public String osmTileBase() {
        return enabled ? prefix(OSM_PROXY_PATH) : OSM_UPSTREAM_BASE;
    }

    /** Frontend hands this to Leaflet as the WMS endpoint; Leaflet appends
     *  query params. NDFD's {@code TIME} parameter ends up in nginx's cache
     *  key (so different forecast windows cache as separate entries). */
    public String ndfdWmsBase() {
        return enabled ? prefix(NDFD_PROXY_PATH) : NDFD_UPSTREAM_BASE;
    }

    /** Base URL for forecast condition icons. {@code WeatherService}
     *  rewrites {@code properties.icon} URLs from {@code https://api.weather.gov/icons/...}
     *  to {@code <wxIconsBase>/icons/...} when the proxy is enabled. */
    public String wxIconsBase() {
        return enabled ? prefix(ICONS_PROXY_PATH) : ICONS_UPSTREAM_BASE;
    }

    /** Returns the CSP {@code img-src} directive contents (without the
     *  directive name itself). Public so {@code SecurityHeadersFilter} can
     *  consult it without duplicating the URL→origin logic. */
    public String cspImgSrc() {
        // 'self' covers same-origin fetches (relative-path proxy mode and the
        // app's own static assets); data: covers Leaflet's inline tile data;
        // unpkg.com covers Leaflet's default marker icons.
        StringBuilder sb = new StringBuilder("'self' data: https://unpkg.com");
        if (!enabled) {
            sb.append(" https://*.tile.openstreetmap.org");
            sb.append(" https://digital.weather.gov");
            sb.append(" https://api.weather.gov");
        } else if (!baseUrl.isEmpty()) {
            // Absolute-base mode: whitelist the proxy origin only.
            sb.append(' ').append(originOf(baseUrl));
        }
        return sb.toString();
    }

    /** Rewrite an upstream weather.gov icon URL into the proxy form when the
     *  proxy is enabled; leaves all other inputs (and all inputs in
     *  upstream-direct mode) untouched. */
    public String rewriteIconUrl(String iconUrl) {
        if (!enabled || iconUrl == null) {
            return iconUrl;
        }
        if (iconUrl.startsWith(ICONS_UPSTREAM_BASE + "/icons/")) {
            String suffix = iconUrl.substring(ICONS_UPSTREAM_BASE.length());
            return prefix(ICONS_PROXY_PATH) + suffix.substring("/icons".length());
        }
        return iconUrl;
    }

    private String prefix(String path) {
        return baseUrl.isEmpty() ? path : baseUrl + path;
    }

    private static String originOf(String absoluteUrl) {
        // Strip everything after scheme://host[:port]. The base URL is
        // controlled by the operator, so we don't bother with java.net.URI
        // for what amounts to a substring.
        int schemeEnd = absoluteUrl.indexOf("://");
        if (schemeEnd < 0) return absoluteUrl;
        int pathStart = absoluteUrl.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? absoluteUrl : absoluteUrl.substring(0, pathStart);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
