package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.config.TileProxyConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Serves the runtime tile/icon URL bases the frontend uses when constructing
 * map tile, WMS overlay, and forecast-icon URLs. The frontend reads this
 * once at startup; switching modes is a Spring restart away.
 */
@RestController
@RequestMapping("/api/config")
public class TileConfigController {

    private final TileProxyConfig tileProxyConfig;

    public TileConfigController(TileProxyConfig tileProxyConfig) {
        this.tileProxyConfig = tileProxyConfig;
    }

    @GetMapping("/tiles")
    public Map<String, Object> tiles() {
        return Map.of(
                "proxyEnabled",  tileProxyConfig.isEnabled(),
                "osmTileBase",   tileProxyConfig.osmTileBase(),
                "ndfdWmsBase",   tileProxyConfig.ndfdWmsBase(),
                "wxIconsBase",   tileProxyConfig.wxIconsBase()
        );
    }
}
