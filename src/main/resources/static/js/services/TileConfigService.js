/**
 * Tile Config Service
 *
 * Loads the runtime tile/WMS/icon URL bases from the backend at app startup.
 * The result drives MapManager (OSM tiles), LayerManager (NDFD WMS), and
 * any other consumer that needs to know whether tiles go through the local
 * nginx proxy or directly to upstream public services.
 *
 * Phase 4 of LOCAL_CACHING_HOSTING.md. Switching modes is a backend
 * env-var flip + restart; the frontend has no concept of "fallback after
 * a runtime failure" — if the backend says proxy is enabled and nginx is
 * down, fix nginx (or flip the env var off and restart bootRun).
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.TileConfig = {

    // Defaults match the disabled-proxy mode: hit upstreams directly. Used
    // both as the cold-start placeholder and as the fallback if the
    // /api/config/tiles fetch fails (so the map still renders).
    DEFAULTS: {
        proxyEnabled: false,
        osmTileBase:  'https://{s}.tile.openstreetmap.org',
        ndfdWmsBase:  'https://digital.weather.gov/ndfd/wms',
        wxIconsBase:  'https://api.weather.gov'
    },

    config: null,

    /**
     * Fetch /api/config/tiles once. Returns a promise resolving to the
     * config object; on failure, populates DEFAULTS and resolves anyway
     * so app initialisation never blocks on this call.
     */
    load: function() {
        const self = this;
        return fetch('/api/config/tiles')
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.json();
            })
            .then(function(data) {
                self.config = data;
                return data;
            })
            .catch(function(err) {
                console.warn('Tile config fetch failed; falling back to upstream defaults:', err);
                self.config = Object.assign({}, self.DEFAULTS);
                return self.config;
            });
    },

    /** Returns the loaded config, or DEFAULTS if load() hasn't completed. */
    get: function() {
        return this.config || this.DEFAULTS;
    }
};
