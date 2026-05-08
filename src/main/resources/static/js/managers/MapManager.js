/**
 * Map Manager
 * Handles map initialization, controls, and core map operations
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Map = {
    
    // Map instance and configuration
    map: null,
    userLocationMarker: null,
    userLocation: { lat: null, lng: null, name: null, timezoneName: '', timezoneStdOffset: '', timezoneDstOffset: '', timezoneStdAbbr: '', timezoneDstAbbr: '' },
    
    // Configuration constants
    DEFAULT_LAT: 39.8283,
    DEFAULT_LNG: -98.5795,
    DEFAULT_ZOOM: 4,
    USER_ZOOM: 13,
    
    /**
     * Initialize the map with specified coordinates and zoom level
     * @param {number} lat - Latitude for map center
     * @param {number} lng - Longitude for map center
     * @param {number} zoom - Zoom level
     */
    initialize: function(lat, lng, zoom) {
        this.map = L.map('map').setView([lat, lng], zoom);

        const tileBase = window.TripWeather.Services.TileConfig.get().osmTileBase;
        L.tileLayer(tileBase + '/{z}/{x}/{y}.png', {
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 19
        }).addTo(this.map);

        this.userLocation.lat = window.TripWeather.Utils.Helpers.formatCoordinate(lat);
        this.userLocation.lng = window.TripWeather.Utils.Helpers.formatCoordinate(lng);
        
        this.userLocationMarker = L.marker([lat, lng]).addTo(this.map);
        this.updateUserLocationPopup();
        this.userLocationMarker.openPopup();
        
        this.map.on('click', this.onMapClick.bind(this));
        this.addRecenterControl();
        
        return this.map;
    },

    /**
     * Add recenter control to the map
     */
    addRecenterControl: function() {
        const RecenterControl = L.Control.extend({
            options: {
                position: 'topleft'
            },
            
            onAdd: function(map) {
                const container = L.DomUtil.create('div', 'leaflet-bar recenter-control');
                container.title = 'Recenter on my location';
                
                window.TripWeather.Utils.IconLoader.loadSvgIcon('icons/crosshair.svg', container, 'recenter-icon');
                
                L.DomEvent.on(container, 'click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    L.DomEvent.preventDefault(e);
                    window.TripWeather.Managers.Map.recenterOnUserLocation();
                });
                
                return container;
            }
        });
        
        this.map.addControl(new RecenterControl());
    },

    /**
     * Handle map click events
     * @param {L.MouseEvent} e - Leaflet mouse event
     */
    onMapClick: function(e) {
        // This will be handled by the WaypointManager
        // Emit a custom event or call a global handler
        if (window.TripWeather.Managers.Waypoint) {
            window.TripWeather.Managers.Waypoint.handleMapClick(e);
        }
    },

    // Geolocation tuning. Goal is sub-100m accuracy (street/building level — better than
    // city-level Wi-Fi triangulation). HIGH_ACCURACY_TIMEOUT_MS caps how long we keep the
    // GPS hot waiting for a precise fix.
    ACCURACY_GOAL_M: 100,
    HIGH_ACCURACY_TIMEOUT_MS: 30000,

    /**
     * High-accuracy geolocation via watchPosition. Reports each accuracy improvement via
     * options.onPosition; stops once accuracy <= ACCURACY_GOAL_M or HIGH_ACCURACY_TIMEOUT_MS
     * elapses. options.onError fires once if the watch fails before any position arrives.
     *
     * maxCacheAgeMs=0 forces a fresh acquisition (used by the "Update" button); any non-zero
     * value lets the browser return a cached position up to that age (used on initial load).
     *
     * A two-phase pattern (fast coarse fix + refining watch) was tried but the coarse phase
     * never resolved on desktop browsers without Wi-Fi positioning data — added complexity
     * without delivering value. Cache hits cover the fast-path use case.
     */
    acquireUserLocation: function(options) {
        const ACCURACY_GOAL_M = this.ACCURACY_GOAL_M;
        const HIGH_ACCURACY_TIMEOUT_MS = this.HIGH_ACCURACY_TIMEOUT_MS;

        // Catches "no prompt ever appears" failures (insecure context, no
        // geolocation API) up front, before we burn time on a watch that the
        // browser will silently reject.
        const precheck = window.TripWeather.Utils.GeolocationDiagnostics.precheck();
        if (!precheck.ok) {
            if (options.onError) options.onError(precheck);
            return;
        }

        let bestAccuracy = Infinity;
        let watchId = null;
        let watchTimeoutId = null;
        let errorFired = false;

        const stopWatch = function() {
            if (watchId !== null) {
                navigator.geolocation.clearWatch(watchId);
                watchId = null;
            }
            if (watchTimeoutId !== null) {
                clearTimeout(watchTimeoutId);
                watchTimeoutId = null;
            }
        };

        const handlePosition = function(position) {
            if (position.coords.accuracy < bestAccuracy) {
                bestAccuracy = position.coords.accuracy;
                options.onPosition(position);
            }
            if (position.coords.accuracy <= ACCURACY_GOAL_M) {
                stopWatch();
            }
        };

        watchId = navigator.geolocation.watchPosition(
            handlePosition,
            function(error) {
                console.warn('Geolocation failed:', error.message);
                stopWatch();
                if (errorFired || bestAccuracy < Infinity) {
                    return;
                }
                errorFired = true;
                options.onError(error);
            },
            {
                enableHighAccuracy: true,
                timeout: HIGH_ACCURACY_TIMEOUT_MS,
                maximumAge: options.maxCacheAgeMs
            }
        );

        watchTimeoutId = setTimeout(stopWatch, HIGH_ACCURACY_TIMEOUT_MS);
    },

    /**
     * Get user's current location and recenter map. Bypasses the browser's geolocation
     * cache (maximumAge: 0) so the fresh fix replaces whatever was cached before.
     */
    recenterOnUserLocation: function() {
        if (!("geolocation" in navigator)) {
            window.TripWeather.Utils.Helpers.showToast('Geolocation is not supported by your browser.', 'warning');
            return;
        }

        const self = this;
        const helpers = window.TripWeather.Utils.Helpers;
        helpers.showLoading('location-loading-overlay');
        let firstFixShown = false;

        this.acquireUserLocation({
            maxCacheAgeMs: 0,
            onPosition: function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;
                const currentZoom = self.map.getZoom();

                self.userLocation.lat = helpers.formatCoordinate(lat);
                self.userLocation.lng = helpers.formatCoordinate(lng);
                self.map.setView([lat, lng], currentZoom);

                if (self.userLocationMarker) {
                    self.userLocationMarker.setLatLng([lat, lng]);
                } else {
                    self.userLocationMarker = L.marker([lat, lng]).addTo(self.map);
                }
                self.updateUserLocationPopup();
                self.userLocationMarker.openPopup();

                if (!firstFixShown) {
                    firstFixShown = true;
                    self.fetchLocationInfo().finally(function() {
                        helpers.hideLoading('location-loading-overlay');
                    });
                } else {
                    // Refining update — re-fetch location info quietly in case the more
                    // precise fix crosses a timezone or locality boundary.
                    self.fetchLocationInfo();
                }
            },
            onError: function(error) {
                console.warn('Geolocation error:', error && error.message, 'code=', error && error.code);
                helpers.hideLoading('location-loading-overlay');
                helpers.showToast(
                    window.TripWeather.Utils.GeolocationDiagnostics.describeError(error),
                    'error');
            }
        });
    },

    /**
     * Update the popup content for the user-location marker. Returns a DOM element so
     * buttons can use addEventListener instead of inline onclick, and so text fields
     * coming from the geocoding API are set via textContent (no interpolation).
     */
    updateUserLocationPopup: function() {
        if (!this.userLocationMarker) return;

        const helpers = window.TripWeather.Utils.Helpers;
        const container = document.createElement('div');

        // Display-only markup: interpolate numeric/formatted values only, escape any
        // external strings (name/timezone come from the geocoding API).
        let html = '<strong>Your Location</strong><br>';
        html += `Latitude: ${helpers.escapeHtml(this.userLocation.lat)}<br>`;
        html += `Longitude: ${helpers.escapeHtml(this.userLocation.lng)}<br>`;
        html += `Elevation: ${helpers.escapeHtml(helpers.formatElevation(this.userLocation.alt))}<br>`;

        if (this.userLocation.name) {
            html += `<br><br><strong>${helpers.escapeHtml(this.userLocation.name)}</strong>`;
        }

        if (this.userLocation.timezoneName) {
            html += `<br>Timezone: ${helpers.escapeHtml(this.userLocation.timezoneName)}`;
        }

        container.innerHTML = html;

        // Interactive content: build with createElement + addEventListener.
        const actions = document.createElement('div');
        actions.style.cssText = 'display: flex; gap: 8px; justify-content: center; margin-top: 12px;';

        const updateBtn = document.createElement('button');
        updateBtn.type = 'button';
        updateBtn.textContent = 'Update';
        updateBtn.style.cssText = 'background-color: #3498db; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500;';
        updateBtn.addEventListener('click', function() {
            window.TripWeather.Managers.Map.refreshUserLocation();
        });

        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.textContent = 'Add To Waypoints';
        addBtn.style.cssText = 'background-color: #27ae60; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500;';
        addBtn.addEventListener('click', function() {
            window.TripWeather.Managers.Map.addCurrentLocationAsWaypoint();
        });

        actions.appendChild(updateBtn);
        actions.appendChild(addBtn);
        container.appendChild(actions);

        this.userLocationMarker.bindPopup(container);
    },

    /**
     * Fetch user location info using reverse geocoding
     */
    fetchLocationInfo: function() {
        const self = this;
        return window.TripWeather.Services.Location.getLocationInfo(
            this.userLocation.lat,
            this.userLocation.lng
        ).then(function(locationInfo) {
            self.userLocation.name = locationInfo.locationName;
            self.userLocation.alt = locationInfo.elevation;
            // Store all timezone information
            self.userLocation.timezoneName = locationInfo.timezoneName || '';
            self.userLocation.timezoneStdOffset = locationInfo.timezoneStdOffset || '';
            self.userLocation.timezoneDstOffset = locationInfo.timezoneDstOffset || '';
            self.userLocation.timezoneStdAbbr = locationInfo.timezoneStdAbbr || '';
            self.userLocation.timezoneDstAbbr = locationInfo.timezoneDstAbbr || '';
            self.updateUserLocationPopup();
        }).catch(function(error) {
            console.warn('Failed to fetch user location name:', error);
        });
    },

    // Initial-load geolocation cache window. A page reload within this window can re-use the
    // browser's cached high-accuracy fix instantly. The "Update" button bypasses this cache
    // (maximumAge: 0) when the user wants a fresh acquisition.
    INITIAL_LOAD_CACHE_MS: 600000,

    /**
     * Initialize map with user location or default location.
     */
    initializeWithUserLocation: function() {
        if (!("geolocation" in navigator)) {
            console.log('Geolocation not supported, using default location');
            this.initialize(this.DEFAULT_LAT, this.DEFAULT_LNG, this.DEFAULT_ZOOM);
            return;
        }

        const self = this;
        const helpers = window.TripWeather.Utils.Helpers;
        const diag = window.TripWeather.Utils.GeolocationDiagnostics;
        helpers.showLoading('location-loading-overlay');
        let mapInitialized = false;
        // Either the watch's onError or the Permissions probe can announce
        // the permission problem. Whichever wins suppresses the other so the
        // user only sees one toast.
        let diagnosticToastShown = false;

        // Proactive probe: if the browser already remembers a denied decision,
        // we can tell the user before the watch eventually times out — and on
        // some iOS Safari versions watchPosition never fires onError at all
        // when permission is denied, so this is the only signal we'll get.
        diag.queryPermissionState().then(function(state) {
            if (state === 'denied' && !mapInitialized && !diagnosticToastShown) {
                diagnosticToastShown = true;
                helpers.showToast(
                    diag.describeError({ code: diag.PERMISSION_DENIED }),
                    'warning');
            }
        });

        this.acquireUserLocation({
            maxCacheAgeMs: this.INITIAL_LOAD_CACHE_MS,
            onPosition: function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;

                if (!mapInitialized) {
                    mapInitialized = true;
                    self.initialize(lat, lng, self.USER_ZOOM);
                    self.fetchLocationInfo().finally(function() {
                        helpers.hideLoading('location-loading-overlay');
                    });
                } else {
                    // Phase 2 refining update — recenter and move the existing marker silently.
                    const currentZoom = self.map.getZoom();
                    self.userLocation.lat = helpers.formatCoordinate(lat);
                    self.userLocation.lng = helpers.formatCoordinate(lng);
                    self.map.setView([lat, lng], currentZoom);
                    if (self.userLocationMarker) {
                        self.userLocationMarker.setLatLng([lat, lng]);
                    }
                    self.fetchLocationInfo();
                }
            },
            onError: function(error) {
                console.warn('Geolocation error:', error && error.message, 'code=', error && error.code);
                console.log('Using default location (center of USA)');
                helpers.hideLoading('location-loading-overlay');
                self.initialize(self.DEFAULT_LAT, self.DEFAULT_LNG, self.DEFAULT_ZOOM);
                // Surface the underlying cause so users on iOS/Safari can self-diagnose
                // (HTTPS missing, denied permission, OS-level Location Services off).
                // Previously this path was silent and people just saw the default map.
                if (!diagnosticToastShown) {
                    diagnosticToastShown = true;
                    helpers.showToast(diag.describeError(error), 'warning');
                }
            }
        });
    },

    /**
     * Set map cursor style
     * @param {string} cursorStyle - CSS cursor style
     */
    setCursor: function(cursorStyle) {
        if (this.map) {
            this.map.getContainer().style.cursor = cursorStyle;
        }
    },

    /**
     * Center map on specific coordinates
     * @param {number} lat - Latitude
     * @param {number} lng - Longitude
     * @param {number} zoom - Zoom level (optional)
     */
    centerOn: function(lat, lng, zoom) {
        if (!this.map) return;
        
        const targetZoom = zoom !== undefined ? zoom : this.map.getZoom();
        this.map.setView([lat, lng], targetZoom);
    },

    /**
     * Fit map bounds to show all coordinates
     * @param {Array} coordinates - Array of [lat, lng] coordinates
     * @param {object} options - FitBounds options (optional)
     */
    fitBounds: function(coordinates, options) {
        if (!this.map || !coordinates || coordinates.length === 0) return;
        
        const bounds = L.latLngBounds(coordinates);
        const defaultOptions = { padding: [50, 50] };
        const fitOptions = Object.assign(defaultOptions, options || {});
        
        this.map.fitBounds(bounds, fitOptions);
    },

    /**
     * Get current map instance
     * @returns {L.Map} - Leaflet map instance
     */
    getMap: function() {
        return this.map;
    },

    /**
     * Get user location information
     * @returns {object} - User location data
     */
    getUserLocation: function() {
        return this.userLocation;
    },

    /**
     * Refresh user location (same as recenter behavior)
     */
    refreshUserLocation: function() {
        this.recenterOnUserLocation();
    },

    /**
     * Add current location as a waypoint
     */
    addCurrentLocationAsWaypoint: function() {
        if (!this.userLocation.lat || !this.userLocation.lng) {
            window.TripWeather.Utils.Helpers.showToast('User location not available. Please refresh your location first.', 'warning');
            return;
        }
        
        // Create location info object with all current user location data including timezone fields
        const locationInfo = {
            locationName: this.userLocation.name || 'Current Location',
            timezoneName: this.userLocation.timezoneName || '',
            timezoneStdOffset: this.userLocation.timezoneStdOffset || '',
            timezoneDstOffset: this.userLocation.timezoneDstOffset || '',
            timezoneStdAbbr: this.userLocation.timezoneStdAbbr || '',
            timezoneDstAbbr: this.userLocation.timezoneDstAbbr || ''
        };
        
        // Add waypoint using WaypointManager
        if (window.TripWeather.Managers.Waypoint) {
            const waypoint = window.TripWeather.Managers.Waypoint.addWaypoint(
                this.userLocation.lat,
                this.userLocation.lng,
                this.userLocation.alt || 0,
                locationInfo,
                null, // No existing waypoint object
                true // Skip validation for user location (should be valid)
            );
            
            // Close the user location popup
            if (this.userLocationMarker) {
                this.userLocationMarker.closePopup();
            }
            
            // Open popup for the newly added waypoint
            if (waypoint && window.TripWeather.Managers.WaypointRenderer) {
                window.TripWeather.Managers.WaypointRenderer.openWaypointPopup(waypoint.sequence);
            }
        } else {
            console.error('WaypointManager not available');
            window.TripWeather.Utils.Helpers.showToast('Unable to add waypoint. Please try again.', 'error');
        }
    }
};
