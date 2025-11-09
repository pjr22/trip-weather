/**
 * Waypoint Manager
 * Handles waypoint data management and business logic
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Waypoint = {
    
    // Waypoint data and state
    waypoints: [],
    waypointMarkers: [],
    replacingWaypointId: null,
    nextId: 1,
    
    /**
     * Waypoint class constructor
     * @param {number} id - Unique waypoint identifier
     * @param {number} lat - Latitude coordinate
     * @param {number} lng - Longitude coordinate
     */
    Waypoint: function(id, lat, lng) {
        this.id = id;
        this.lat = window.TripWeather.Utils.Helpers.formatCoordinate(lat);
        this.lng = window.TripWeather.Utils.Helpers.formatCoordinate(lng);
        this.date = '';
        this.time = '';
        this.timezone = '';
        this.duration = 0;
        this.locationName = '';
        this.weather = null;
        this.weatherLoading = false;
    },

    /**
     * Initialize the waypoint manager
     */
    initialize: function() {
        this.waypoints = [];
        this.waypointMarkers = [];
        this.replacingWaypointId = null;
        this.nextId = 1;
    },

    /**
     * Handle map click events for waypoint creation/replacement
     * @param {L.MouseEvent} e - Leaflet mouse event
     */
    handleMapClick: function(e) {
        if (this.replacingWaypointId !== null) {
            this.replaceWaypointLocation(this.replacingWaypointId, e.latlng.lat, e.latlng.lng);
            this.replacingWaypointId = null;
            window.TripWeather.Managers.Map.setCursor('');
        } else {
            this.addWaypoint(e.latlng.lat, e.latlng.lng);
        }
    },

    /**
     * Add a new waypoint at the specified coordinates
     * @param {number} lat - Latitude coordinate
     * @param {number} lng - Longitude coordinate
     * @param {object} locationInfo - Optional pre-fetched location information
     * @returns {object} - Created waypoint
     */
    addWaypoint: function(lat, lng, locationInfo) {
        const waypoint = new this.Waypoint(this.nextId++, lat, lng);
        
        // If location info is provided, use it
        if (locationInfo) {
            waypoint.locationName = locationInfo.locationName;
            waypoint.timezone = locationInfo.timezone;
        }
        
        this.waypoints.push(waypoint);
        
        // Add marker to map
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.addMarkerToMap(waypoint, this.waypoints.length);
        }
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        // Update route button state
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.updateButtonState();
        }
        
        // Clear route if needed
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.clearRouteOnWaypointChange('add', this.waypoints.length - 1);
        }
        
        // Fetch location info if not provided
        if (!locationInfo) {
            this.fetchLocationName(waypoint);
        }
        
        return waypoint;
    },

    /**
     * Delete a waypoint by ID
     * @param {number} id - Waypoint ID to delete
     */
    deleteWaypoint: function(id) {
        const index = this.waypoints.findIndex(w => w.id === id);
        if (index === -1) return;
        
        // Remove from waypoints array
        this.waypoints.splice(index, 1);
        
        // Remove marker from map
        const markerIndex = this.waypointMarkers.findIndex(m => m.waypointId === id);
        if (markerIndex !== -1) {
            const map = window.TripWeather.Managers.Map.getMap();
            if (map) {
                map.removeLayer(this.waypointMarkers[markerIndex]);
            }
            this.waypointMarkers.splice(markerIndex, 1);
        }
        
        // Recreate all markers with updated order numbers
        this.refreshAllMarkers();
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        // Update route button state
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.updateButtonState();
        }
        
        // Clear route
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.clearRouteOnWaypointChange('delete');
        }
    },

    /**
     * Replace waypoint location
     * @param {number} waypointId - ID of waypoint to replace
     * @param {number} newLat - New latitude
     * @param {number} newLng - New longitude
     * @param {object} locationInfo - Optional pre-fetched location information
     */
    replaceWaypointLocation: function(waypointId, newLat, newLng, locationInfo) {
        const waypoint = this.waypoints.find(w => w.id === waypointId);
        if (!waypoint) return;
        
        waypoint.lat = window.TripWeather.Utils.Helpers.formatCoordinate(newLat);
        waypoint.lng = window.TripWeather.Utils.Helpers.formatCoordinate(newLng);
        waypoint.weather = null;
        
        // Update location info if provided
        if (locationInfo) {
            waypoint.locationName = locationInfo.locationName;
            waypoint.timezone = locationInfo.timezone;
        } else {
            waypoint.locationName = '';
            waypoint.timezone = '';
        }
        
        // Update marker position
        const index = this.waypoints.findIndex(w => w.id === waypointId);
        if (index !== -1 && window.TripWeather.Managers.WaypointRenderer) {
            const marker = this.waypointMarkers[index];
            if (marker) {
                marker.setLatLng([newLat, newLng]);
                window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        // Clear route
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.clearRouteOnWaypointChange('modify');
        }
        
        // Fetch location info if not provided
        if (!locationInfo) {
            this.fetchLocationName(waypoint);
        }
    },

    /**
     * Update waypoint field value
     * @param {number} id - Waypoint ID
     * @param {string} field - Field name to update
     * @param {*} value - New field value
     */
    updateWaypointField: function(id, field, value) {
        const waypoint = this.waypoints.find(w => w.id === id);
        if (!waypoint) return;
        
        if (field === 'date' || field === 'time' || field === 'locationName' || field === 'timezone') {
            waypoint[field] = value;
            
            // Fetch weather when date and time are set
            if ((field === 'date' || field === 'time') && waypoint.date && waypoint.time) {
                this.fetchWeatherForWaypoint(waypoint);
            }
            
            // Recheck timezone when date or time changes
            if ((field === 'date' || field === 'time') && waypoint.lat && waypoint.lng) {
                this.recheckWaypointTimezone(waypoint);
            }
        }
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
    },

    /**
     * Update waypoint duration
     * @param {number} waypointId - Waypoint ID
     * @param {string} inputValue - Duration input value
     */
    updateWaypointDuration: function(waypointId, inputValue) {
        const waypoint = this.waypoints.find(w => w.id === waypointId);
        if (!waypoint) return;
        
        const validation = window.TripWeather.Utils.Duration.validateDurationInput(inputValue);
        
        // Update the input field with corrected value if needed
        const inputElement = document.querySelector(`tr[data-waypoint-id="${waypointId}"] .duration-input`);
        if (inputElement && !validation.isValid) {
            inputElement.value = validation.correctedValue;
        }
        
        // Update waypoint duration
        waypoint.duration = validation.minutes;
        
        // Update marker popup
        const index = this.waypoints.findIndex(w => w.id === waypointId);
        if (index !== -1 && window.TripWeather.Managers.WaypointRenderer) {
            const marker = this.waypointMarkers[index];
            if (marker) {
                window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
    },

    /**
     * Increment waypoint duration
     * @param {number} waypointId - Waypoint ID
     * @param {number} incrementMinutes - Minutes to increment
     */
    incrementWaypointDuration: function(waypointId, incrementMinutes) {
        const waypoint = this.waypoints.find(w => w.id === waypointId);
        if (!waypoint) return;
        
        const currentFormatted = window.TripWeather.Utils.Duration.formatDuration(waypoint.duration);
        const newFormatted = window.TripWeather.Utils.Duration.incrementDuration(currentFormatted, incrementMinutes);
        const newMinutes = window.TripWeather.Utils.Duration.parseDuration(newFormatted);
        
        // Update waypoint
        waypoint.duration = newMinutes;
        
        // Update input field
        const inputElement = document.querySelector(`tr[data-waypoint-id="${waypointId}"] .duration-input`);
        if (inputElement) {
            inputElement.value = newFormatted;
        }
        
        // Update marker popup
        const index = this.waypoints.findIndex(w => w.id === waypointId);
        if (index !== -1 && window.TripWeather.Managers.WaypointRenderer) {
            const marker = this.waypointMarkers[index];
            if (marker) {
                window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
    },

    /**
     * Reorder waypoints
     * @param {number} draggedId - ID of waypoint being dragged
     * @param {number} targetId - ID of target waypoint
     */
    reorderWaypoints: function(draggedId, targetId) {
        const draggedIndex = this.waypoints.findIndex(w => w.id === draggedId);
        const targetIndex = this.waypoints.findIndex(w => w.id === targetId);
        
        if (draggedIndex === -1 || targetIndex === -1) return;
        
        // Don't do anything if dragging to the same position
        if (draggedIndex === targetIndex) return;
        
        const [removed] = this.waypoints.splice(draggedIndex, 1);
        
        // If we removed an item before the target, the target index shifts left by 1
        const adjustedTargetIndex = draggedIndex < targetIndex ? targetIndex - 1 : targetIndex;
        
        this.waypoints.splice(adjustedTargetIndex, 0, removed);
        
        this.refreshAllMarkers();
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        // Clear route
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.clearRouteOnWaypointChange('reorder');
        }
    },

    /**
     * Move waypoint to end of list
     * @param {number} draggedId - ID of waypoint to move
     */
    moveToEnd: function(draggedId) {
        const draggedIndex = this.waypoints.findIndex(w => w.id === draggedId);
        
        if (draggedIndex === -1) return;
        
        // Don't do anything if already at the end
        if (draggedIndex === this.waypoints.length - 1) return;
        
        const [removed] = this.waypoints.splice(draggedIndex, 1);
        this.waypoints.push(removed);
        
        this.refreshAllMarkers();
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        // Clear route
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.clearRouteOnWaypointChange('reorder');
        }
    },

    /**
     * Refresh all waypoint markers with updated order numbers
     */
    refreshAllMarkers: function() {
        // Remove all existing markers
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            this.waypointMarkers.forEach(marker => map.removeLayer(marker));
        }
        this.waypointMarkers = [];
        
        // Recreate all markers with new order numbers
        if (window.TripWeather.Managers.WaypointRenderer) {
            this.waypoints.forEach((waypoint, index) => {
                window.TripWeather.Managers.WaypointRenderer.addMarkerToMap(waypoint, index + 1);
            });
        }
    },

    /**
     * Fetch location name for a waypoint
     * @param {object} waypoint - Waypoint object
     */
    fetchLocationName: function(waypoint) {
        return window.TripWeather.Services.Location.getLocationInfo(waypoint.lat, waypoint.lng)
            .then(function(locationInfo) {
                waypoint.locationName = locationInfo.locationName;
                waypoint.timezone = locationInfo.timezone;
                
                // Update UI
                if (window.TripWeather.Managers.WaypointRenderer) {
                    window.TripWeather.Managers.WaypointRenderer.updateTable();
                    window.TripWeather.Managers.WaypointRenderer.updateMarkerWithLocation(waypoint);
                }
            })
            .catch(function(error) {
                console.warn('Failed to fetch location name:', error);
            });
    },

    /**
     * Recheck timezone for a waypoint when date or time changes
     * @param {object} waypoint - Waypoint to recheck timezone for
     */
    recheckWaypointTimezone: function(waypoint) {
        return window.TripWeather.Services.Location.reverseGeocode(waypoint.lat, waypoint.lng)
            .then(function(data) {
                const locationInfo = window.TripWeather.Utils.Helpers.parseLocationData(data);
                
                // Get the target date for DST calculation
                const targetDate = waypoint.date && waypoint.time ? `${waypoint.date} ${waypoint.time}` : null;
                
                // Update timezone with DST-aware calculation
                if (locationInfo.timezoneName) {
                    waypoint.timezone = window.TripWeather.Utils.Timezone.getTimezoneAbbr(locationInfo.timezoneName, targetDate);
                } else {
                    waypoint.timezone = locationInfo.timezone;
                }
                
                // Update the table to show the new timezone
                if (window.TripWeather.Managers.WaypointRenderer) {
                    window.TripWeather.Managers.WaypointRenderer.updateTable();
                }
                
                // Update marker popup to show new timezone
                const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.id === waypoint.id);
                if (index !== -1 && window.TripWeather.Managers.WaypointRenderer) {
                    const marker = window.TripWeather.Managers.Waypoint.waypointMarkers[index];
                    if (marker) {
                        window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
                    }
                }
                
                console.log(`Timezone rechecked for waypoint ${waypoint.id}: ${waypoint.timezone}`);
            })
            .catch(function(error) {
                console.warn('Failed to recheck timezone:', error);
            });
    },

    /**
     * Fetch weather for a waypoint
     * @param {object} waypoint - Waypoint object
     */
    fetchWeatherForWaypoint: function(waypoint) {
        waypoint.weatherLoading = true;
        waypoint.weather = null;
        
        // Update UI to show loading state
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        return window.TripWeather.Services.Weather.getWeatherForWaypoint(waypoint)
            .then(function(weatherData) {
                waypoint.weather = weatherData;
                waypoint.weatherLoading = false;
                
                // Update UI
                if (window.TripWeather.Managers.WaypointRenderer) {
                    window.TripWeather.Managers.WaypointRenderer.updateTable();
                    window.TripWeather.Managers.WaypointRenderer.updateMarkerWithWeather(waypoint);
                }
            })
            .catch(function(error) {
                waypoint.weather = { error: 'Failed to fetch weather data' };
                waypoint.weatherLoading = false;
                
                // Update UI
                if (window.TripWeather.Managers.WaypointRenderer) {
                    window.TripWeather.Managers.WaypointRenderer.updateTable();
                }
            });
    },

    /**
     * Get waypoint by ID
     * @param {number} id - Waypoint ID
     * @returns {object|null} - Waypoint object or null if not found
     */
    getWaypoint: function(id) {
        return this.waypoints.find(w => w.id === id) || null;
    },

    /**
     * Get all waypoints
     * @returns {Array} - Array of waypoint objects
     */
    getAllWaypoints: function() {
        return this.waypoints.slice(); // Return copy
    },

    /**
     * Get waypoint count
     * @returns {number} - Number of waypoints
     */
    getWaypointCount: function() {
        return this.waypoints.length;
    },

    /**
     * Center map on waypoint
     * @param {number} waypointId - Waypoint ID
     */
    centerOnWaypoint: function(waypointId) {
        const waypoint = this.getWaypoint(waypointId);
        if (!waypoint) return;
        
        window.TripWeather.Managers.Map.centerOn(waypoint.lat, waypoint.lng);
        
        const index = this.waypoints.findIndex(w => w.id === waypointId);
        if (index !== -1 && this.waypointMarkers[index]) {
            this.waypointMarkers[index].openPopup();
        }
        
        // Highlight table row
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.highlightTableRow(waypointId);
        }
    },

    /**
     * Start waypoint replacement process
     * @param {number} waypointId - Waypoint ID to replace
     */
    selectNewLocationForWaypoint: function(waypointId) {
        this.replacingWaypointId = waypointId;
        window.TripWeather.Managers.Map.setCursor('crosshair');
        window.TripWeather.Utils.Helpers.showAlert('Click on the map to select a new location for this waypoint.');
    },

    /**
     * Get replacing waypoint ID
     * @returns {number|null} - Current replacing waypoint ID or null
     */
    getReplacingWaypointId: function() {
        return this.replacingWaypointId;
    },

    /**
     * Set replacing waypoint ID
     * @param {number|null} waypointId - Waypoint ID or null to clear
     */
    setReplacingWaypointId: function(waypointId) {
        this.replacingWaypointId = waypointId;
    }
};
