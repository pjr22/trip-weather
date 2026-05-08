package com.pjr22.tripweather.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TileProxyConfigTest {

    private static final String OSM_UPSTREAM = "https://{s}.tile.openstreetmap.org";
    private static final String NDFD_UPSTREAM = "https://digital.weather.gov/ndfd/wms";
    private static final String ICONS_UPSTREAM = "https://api.weather.gov";

    @Test
    void disabled_yieldsUpstreamUrlsAndPermissiveImgSrc() {
        TileProxyConfig c = new TileProxyConfig(false, "");

        assertThat(c.osmTileBase()).isEqualTo(OSM_UPSTREAM);
        assertThat(c.ndfdWmsBase()).isEqualTo(NDFD_UPSTREAM);
        assertThat(c.wxIconsBase()).isEqualTo(ICONS_UPSTREAM);

        assertThat(c.cspImgSrc())
                .contains("https://*.tile.openstreetmap.org")
                .contains("https://digital.weather.gov")
                .contains("https://api.weather.gov");

        // Icon rewrite is a no-op when disabled.
        assertThat(c.rewriteIconUrl("https://api.weather.gov/icons/land/day/skc"))
                .isEqualTo("https://api.weather.gov/icons/land/day/skc");
    }

    @Test
    void enabledRelative_yieldsRelativePathsAndStrictImgSrc() {
        TileProxyConfig c = new TileProxyConfig(true, "");

        assertThat(c.osmTileBase()).isEqualTo("/tiles/osm");
        assertThat(c.ndfdWmsBase()).isEqualTo("/tiles/ndfd-wms");
        assertThat(c.wxIconsBase()).isEqualTo("/wx-icons");

        // 'self' covers the same-origin proxy paths; no upstream hostnames remain.
        assertThat(c.cspImgSrc())
                .contains("'self'")
                .doesNotContain("openstreetmap.org")
                .doesNotContain("digital.weather.gov")
                .doesNotContain("api.weather.gov");

        assertThat(c.rewriteIconUrl("https://api.weather.gov/icons/land/day/skc"))
                .isEqualTo("/wx-icons/land/day/skc");
    }

    @Test
    void enabledAbsolute_yieldsAbsoluteUrlsAndOriginScopedImgSrc() {
        TileProxyConfig c = new TileProxyConfig(true, "http://localhost:8091");

        assertThat(c.osmTileBase()).isEqualTo("http://localhost:8091/tiles/osm");
        assertThat(c.ndfdWmsBase()).isEqualTo("http://localhost:8091/tiles/ndfd-wms");
        assertThat(c.wxIconsBase()).isEqualTo("http://localhost:8091/wx-icons");

        assertThat(c.cspImgSrc())
                .contains("'self'")
                .contains("http://localhost:8091")
                .doesNotContain("openstreetmap.org");

        assertThat(c.rewriteIconUrl("https://api.weather.gov/icons/land/day/skc"))
                .isEqualTo("http://localhost:8091/wx-icons/land/day/skc");
    }

    @Test
    void trailingSlashOnBaseUrl_isStrippedSoUrlsDontDoubleUp() {
        TileProxyConfig c = new TileProxyConfig(true, "http://localhost:8091/");

        assertThat(c.osmTileBase()).isEqualTo("http://localhost:8091/tiles/osm");
    }

    @Test
    void rewriteIconUrl_leavesUnrelatedUrlsAlone() {
        TileProxyConfig c = new TileProxyConfig(true, "");

        // Already proxy-form, foreign host, or non-icon path — pass through unchanged.
        assertThat(c.rewriteIconUrl("/wx-icons/foo")).isEqualTo("/wx-icons/foo");
        assertThat(c.rewriteIconUrl("https://example.com/icon.png"))
                .isEqualTo("https://example.com/icon.png");
        assertThat(c.rewriteIconUrl("https://api.weather.gov/points/40,-105"))
                .isEqualTo("https://api.weather.gov/points/40,-105");
        assertThat(c.rewriteIconUrl(null)).isNull();
    }
}
