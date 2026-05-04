/**
 * Export Manager
 * Handles the Export Route button, modal, and file-format downloads.
 * Google Maps integration is added in phase 8.
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Export = {

    // Google Maps directions URL caps the dir/?api=1 form at one origin, one
    // destination, and up to 8 intermediate stops. Routes longer than this
    // get truncated with a warning toast.
    GOOGLE_MAPS_MAX_STOPS: 10,

    initialize: function() {
        const exportBtn = document.getElementById('export-route-btn');
        if (exportBtn) {
            exportBtn.addEventListener('click', this.handleExportClick.bind(this));
        } else {
            console.warn('Export route button not found');
        }

        this.initializeModal();
    },

    initializeModal: function() {
        const modal = document.getElementById('export-modal');
        if (!modal) {
            console.warn('Export modal not found');
            return;
        }

        const closeBtn = modal.querySelector('.close');
        if (closeBtn) {
            closeBtn.addEventListener('click', this.hideModal.bind(this));
        }

        modal.addEventListener('click', function(event) {
            if (event.target === modal) {
                this.hideModal();
            }
        }.bind(this));

        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                this.hideModal();
            }
        }.bind(this));

        modal.querySelectorAll('.export-option-btn').forEach(function(btn) {
            btn.addEventListener('click', function() {
                this.handleFormatClick(btn.dataset.format);
            }.bind(this));
        }.bind(this));
    },

    /**
     * Open the export modal — only if the current route is saved and has
     * at least 2 waypoints. Mirrors the save-first guard already used by
     * Share, plus the same ≥2 waypoint rule the server enforces.
     */
    handleExportClick: function() {
        const route = window.TripWeather.App.currentRoute;
        if (!route || !route.id) {
            window.TripWeather.Managers.UI.showToast(
                'Save the route before exporting.',
                'warning'
            );
            return;
        }

        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        if (!waypoints || waypoints.length < 2) {
            window.TripWeather.Managers.UI.showToast(
                'Add at least 2 waypoints before exporting.',
                'warning'
            );
            return;
        }

        const nameEl = document.getElementById('export-route-name-value');
        if (nameEl) {
            nameEl.textContent = route.name || '(unnamed route)';
        }
        this.showModal();
    },

    showModal: function() {
        const modal = document.getElementById('export-modal');
        if (modal) modal.style.display = 'block';
    },

    hideModal: function() {
        const modal = document.getElementById('export-modal');
        if (modal) modal.style.display = 'none';
    },

    /**
     * Dispatch a format selection. File formats download via window.location;
     * Google Maps will be wired in phase 8.
     * @param {string} format
     */
    handleFormatClick: function(format) {
        const route = window.TripWeather.App.currentRoute;
        if (!route || !route.id) {
            window.TripWeather.Managers.UI.showToast(
                'Save the route before exporting.',
                'warning'
            );
            this.hideModal();
            return;
        }

        if (format === 'gmaps') {
            this.handleGoogleMaps();
            this.hideModal();
            return;
        }

        this.triggerDownload(route.id, format);
        this.hideModal();
    },

    /**
     * Build a Google Maps directions URL from the current waypoints and open
     * it in a new tab. Universal "?api=1" URLs open the Google Maps app on
     * iOS / Android and the website on desktop.
     */
    handleGoogleMaps: function() {
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        if (!waypoints || waypoints.length < 2) {
            window.TripWeather.Managers.UI.showToast(
                'Need at least 2 waypoints to open in Google Maps.',
                'warning'
            );
            return;
        }

        const total = waypoints.length;
        let stops = waypoints;
        if (total > this.GOOGLE_MAPS_MAX_STOPS) {
            stops = waypoints.slice(0, this.GOOGLE_MAPS_MAX_STOPS);
            window.TripWeather.Managers.UI.showToast(
                `Google Maps supports up to ${this.GOOGLE_MAPS_MAX_STOPS} stops; truncating from ${total} waypoints.`,
                'warning'
            );
        }

        const url = this.buildGoogleMapsUrl(stops);
        window.open(url, '_blank', 'noopener');
    },

    /**
     * Construct the Google Maps universal directions URL.
     * @param {Array<{lat:number,lng:number}>} stops - Ordered waypoints, length 2..GOOGLE_MAPS_MAX_STOPS
     * @returns {string} URL
     */
    buildGoogleMapsUrl: function(stops) {
        const first = stops[0];
        const last = stops[stops.length - 1];
        const intermediate = stops.slice(1, -1)
            .map(function(w) { return `${w.lat},${w.lng}`; })
            .join('|');

        const params = [
            'api=1',
            'origin=' + encodeURIComponent(`${first.lat},${first.lng}`),
            'destination=' + encodeURIComponent(`${last.lat},${last.lng}`),
            'travelmode=driving'
        ];
        if (intermediate) {
            params.push('waypoints=' + encodeURIComponent(intermediate));
        }
        return 'https://www.google.com/maps/dir/?' + params.join('&');
    },

    /**
     * Triggering a navigation to the endpoint causes the browser to download
     * the file (server sets Content-Disposition: attachment). Same-origin so
     * cookies aren't a concern.
     * @param {string} routeId
     * @param {string} format
     */
    triggerDownload: function(routeId, format) {
        const url = `/api/routes/${encodeURIComponent(routeId)}/export.${encodeURIComponent(format)}`;
        window.location.href = url;
    }
};
