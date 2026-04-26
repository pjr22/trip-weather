/**
 * Nav Map Adapter
 * Thin interface over the map engine so the navigation core can stay
 * map-engine-agnostic. v1 ships only the Leaflet adapter; a MapLibre adapter
 * with heading-up rotation is planned as a v2 alternative (see NAVIGATION_PLAN.md §6.6).
 *
 * Adapter contract:
 *   initialize(map)
 *   enterNavMode()
 *   exitNavMode()
 *   updateUserPosition(lat, lng, headingDegrees)
 *   followCamera(lat, lng)
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};
window.TripWeather.Nav.MapAdapter = window.TripWeather.Nav.MapAdapter || {};

window.TripWeather.Nav.MapAdapter.Leaflet = {

    map: null,
    navUserMarker: null,
    savedView: null,

    initialize: function(map) {
        this.map = map;
    },

    enterNavMode: function() {
        if (!this.map) return;
        this.savedView = {
            center: this.map.getCenter(),
            zoom: this.map.getZoom()
        };
    },

    exitNavMode: function() {
        if (this.navUserMarker) {
            this.navUserMarker.remove();
            this.navUserMarker = null;
        }
        if (this.savedView && this.map) {
            this.map.setView(this.savedView.center, this.savedView.zoom);
            this.savedView = null;
        }
    },

    updateUserPosition: function(lat, lng, headingDegrees) {
        if (!this.map) return;
        const icon = this._makeNavIcon(headingDegrees);
        if (!this.navUserMarker) {
            this.navUserMarker = L.marker([lat, lng], {
                icon: icon,
                interactive: false,
                keyboard: false,
                zIndexOffset: 1000
            }).addTo(this.map);
        } else {
            this.navUserMarker.setLatLng([lat, lng]);
            this.navUserMarker.setIcon(icon);
        }
    },

    followCamera: function(lat, lng) {
        if (!this.map) return;
        const zoom = window.TripWeather.Nav.Constants.NAV_FOLLOW_ZOOM;
        this.map.setView([lat, lng], zoom, { animate: false });
    },

    _makeNavIcon: function(headingDegrees) {
        const rotation = Number.isFinite(headingDegrees) ? headingDegrees : 0;
        const html = '<div class="nav-arrow" style="transform: rotate(' + rotation + 'deg);"></div>';
        return L.divIcon({
            className: 'nav-user-marker',
            html: html,
            iconSize: [40, 40],
            iconAnchor: [20, 20]
        });
    }
};
