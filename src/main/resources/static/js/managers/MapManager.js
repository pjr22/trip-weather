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

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
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

    /**
     * Get user's current location and recenter map
     */
    recenterOnUserLocation: function() {
        if (!("geolocation" in navigator)) {
            window.TripWeather.Utils.Helpers.showToast('Geolocation is not supported by your browser.', 'warning');
            return;
        }
        
        window.TripWeather.Utils.Helpers.showLoading('location-loading-overlay');
        
        navigator.geolocation.getCurrentPosition(
            function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;
                const currentZoom = window.TripWeather.Managers.Map.map.getZoom();
                
                window.TripWeather.Managers.Map.userLocation.lat = window.TripWeather.Utils.Helpers.formatCoordinate(lat);
                window.TripWeather.Managers.Map.userLocation.lng = window.TripWeather.Utils.Helpers.formatCoordinate(lng);
                
                window.TripWeather.Managers.Map.map.setView([lat, lng], currentZoom);
                
                if (window.TripWeather.Managers.Map.userLocationMarker) {
                    window.TripWeather.Managers.Map.userLocationMarker.setLatLng([lat, lng]);
                    window.TripWeather.Managers.Map.updateUserLocationPopup();
                    window.TripWeather.Managers.Map.userLocationMarker.openPopup();
                } else {
                    window.TripWeather.Managers.Map.userLocationMarker = L.marker([lat, lng]).addTo(window.TripWeather.Managers.Map.map);
                    window.TripWeather.Managers.Map.updateUserLocationPopup();
                    window.TripWeather.Managers.Map.userLocationMarker.openPopup();
                }
                
                window.TripWeather.Managers.Map.fetchLocationInfo().finally(function() {
                    window.TripWeather.Utils.Helpers.hideLoading('location-loading-overlay');
                });
            },
            function(error) {
                console.warn('Geolocation error:', error.message);
                window.TripWeather.Utils.Helpers.hideLoading('location-loading-overlay');
                window.TripWeather.Utils.Helpers.showToast('Unable to get your current location. Please check your browser permissions.', 'error');
            },
            {
                enableHighAccuracy: true,
                timeout: 5000,
                maximumAge: 0
            }
        );
    },

    /**
     * Update the popup content for user location marker
     */
    updateUserLocationPopup: function() {
        if (!this.userLocationMarker) return;
        
        let popupContent = '<strong>Your Location</strong><br>';
        popupContent += `Latitude: ${this.userLocation.lat}<br>`;
        popupContent += `Longitude: ${this.userLocation.lng}<br>`;
        popupContent += `Elevation: ${window.TripWeather.Utils.Helpers.formatElevation(this.userLocation.alt)}<br>`;
        
        if (this.userLocation.name) {
            popupContent += `<br><br><strong>${this.userLocation.name}</strong>`;
        }
        
        if (this.userLocation.timezoneName) {
            popupContent += `<br>Timezone: ${this.userLocation.timezoneName}`;
        }
        
        // Add action buttons at the bottom
        popupContent += `<br><br><div style="display: flex; gap: 8px; justify-content: center;">`;
        popupContent += `<button onclick="window.TripWeather.Managers.Map.refreshUserLocation()" style="background-color: #3498db; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500;">Update</button>`;
        popupContent += `<button onclick="window.TripWeather.Managers.Map.addCurrentLocationAsWaypoint()" style="background-color: #27ae60; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500;">Add To Waypoints</button>`;
        popupContent += `</div>`;
        
        this.userLocationMarker.bindPopup(popupContent);
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

    /**
     * Initialize map with user location or default location
     */
    initializeWithUserLocation: function() {
        if ("geolocation" in navigator) {
            window.TripWeather.Utils.Helpers.showLoading('location-loading-overlay');
            
            navigator.geolocation.getCurrentPosition(
                function(position) {
                    const lat = position.coords.latitude;
                    const lng = position.coords.longitude;
                    window.TripWeather.Managers.Map.initialize(lat, lng, window.TripWeather.Managers.Map.USER_ZOOM);
                    
                    window.TripWeather.Managers.Map.fetchLocationInfo().finally(function() {
                        window.TripWeather.Utils.Helpers.hideLoading('location-loading-overlay');
                    });
                },
                function(error) {
                    console.warn('Geolocation error:', error.message);
                    console.log('Using default location (center of USA)');
                    window.TripWeather.Utils.Helpers.hideLoading('location-loading-overlay');
                    window.TripWeather.Managers.Map.initialize(
                        window.TripWeather.Managers.Map.DEFAULT_LAT, 
                        window.TripWeather.Managers.Map.DEFAULT_LNG, 
                        window.TripWeather.Managers.Map.DEFAULT_ZOOM
                    );
                },
                {
                    enableHighAccuracy: true,
                    timeout: 5000,
                    maximumAge: 0
                }
            );
        } else {
            console.log('Geolocation not supported, using default location');
            this.initialize(this.DEFAULT_LAT, this.DEFAULT_LNG, this.DEFAULT_ZOOM);
        }
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
                locationInfo
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
