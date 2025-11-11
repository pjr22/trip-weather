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
    replacingWaypointSequence: null,
    nextSequence: 1,
    
    /**
     * Waypoint class constructor
     * @param {number} sequence - Sequence number for ordering
     * @param {string} uuid - UUID for entity identification (null for new waypoints)
     * @param {number} lat - Latitude coordinate
     * @param {number} lng - Longitude coordinate
     */
    Waypoint: function(sequence, uuid, lat, lng) {
        this.sequence = sequence;
        this.uuid = uuid; // null for new waypoints, will be set by backend
        this.lat = window.TripWeather.Utils.Helpers.formatCoordinate(lat);
        this.lng = window.TripWeather.Utils.Helpers.formatCoordinate(lng);
        this.date = '';
        this.time = '';
        // Timezone information from API
        this.timezoneName = '';         // Timezone proper name e.g., "America/Denver"
        this.timezoneStdOffset = '';    // Standard time offset e.g., "-07:00"
        this.timezoneDstOffset = '';    // Daylight saving time offset e.g., "-06:00"
        this.timezoneStdAbbr = '';      // Standard time abbreviation e.g., "MST"
        this.timezoneDstAbbr = '';      // Daylight saving time abbreviation e.g., "MDT"
        this.duration = 0;
        this.locationName = '';
        this.weather = null;
        this.weatherLoading = false;
    },

    /**
     * Initialize waypoint manager
     */
    initialize: function() {
        this.waypoints = [];
        this.waypointMarkers = [];
        this.replacingWaypointSequence = null;
        this.nextSequence = 1;
    },

    /**
     * Handle map click events for waypoint creation/replacement
     * @param {L.MouseEvent} e - Leaflet mouse event
     */
    handleMapClick: function(e) {
        if (this.replacingWaypointSequence !== null) {
            this.replaceWaypointLocation(this.replacingWaypointSequence, e.latlng.lat, e.latlng.lng);
            this.replacingWaypointSequence = null;
            window.TripWeather.Managers.Map.setCursor('');
        } else {
            this.addWaypoint(e.latlng.lat, e.latlng.lng);
        }
    },

    /**
     * Add a new waypoint at specified coordinates
     * @param {number} lat - Latitude coordinate
     * @param {number} lng - Longitude coordinate
     * @param {object} locationInfo - Optional pre-fetched location information
     * @param {object} existingWaypoint - Optional existing waypoint object to load
     * @returns {object} - Created waypoint
     */
    addWaypoint: function(lat, lng, locationInfo, existingWaypoint) {
        let waypoint;
        
        if (existingWaypoint) {
            // Use existing waypoint data for loading routes
            waypoint = new this.Waypoint(
                existingWaypoint.sequence || this.nextSequence++,
                existingWaypoint.uuid,
                lat,
                lng
            );
            waypoint.locationName = existingWaypoint.locationName || '';
            waypoint.date = existingWaypoint.date || '';
            waypoint.time = existingWaypoint.time || '';
            waypoint.duration = existingWaypoint.duration || 0;
            
            // Copy timezone information if it exists
            if (existingWaypoint.timezoneName) {
                waypoint.timezoneName = existingWaypoint.timezoneName;
                waypoint.timezoneStdOffset = existingWaypoint.timezoneStdOffset || '';
                waypoint.timezoneDstOffset = existingWaypoint.timezoneDstOffset || '';
                waypoint.timezoneStdAbbr = existingWaypoint.timezoneStdAbbr || '';
                waypoint.timezoneDstAbbr = existingWaypoint.timezoneDstAbbr || '';
            }
            
            // CRITICAL FIX: Update nextSequence to be higher than any existing waypoint sequence
            if (existingWaypoint.sequence && existingWaypoint.sequence >= this.nextSequence) {
                this.nextSequence = existingWaypoint.sequence + 1;
            }
        } else {
            // Create new waypoint for manual addition
            waypoint = new this.Waypoint(this.nextSequence++, null, lat, lng);
            
            // If location info is provided, use it
            if (locationInfo) {
                waypoint.locationName = locationInfo.locationName;
                // Store all timezone information
                waypoint.timezoneName = locationInfo.timezoneName || '';
                waypoint.timezoneStdOffset = locationInfo.timezoneStdOffset || '';
                waypoint.timezoneDstOffset = locationInfo.timezoneDstOffset || '';
                waypoint.timezoneStdAbbr = locationInfo.timezoneStdAbbr || '';
                waypoint.timezoneDstAbbr = locationInfo.timezoneDstAbbr || '';
            }
        }
        
        this.waypoints.push(waypoint);
        
        // Add marker to map and ensure marker array stays synchronized
        if (window.TripWeather.Managers.WaypointRenderer) {
            const marker = window.TripWeather.Managers.WaypointRenderer.addMarkerToMap(waypoint, this.waypoints.length);
            // Ensure the marker array stays in sync with waypoints array
            this.waypointMarkers.push(marker);
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
     * Delete a waypoint by sequence
     * @param {number} sequence - Waypoint sequence to delete
     */
    deleteWaypoint: function(sequence) {
        const index = this.waypoints.findIndex(w => w.sequence === sequence);
        if (index === -1) return;
        
        // Remove from waypoints array
        this.waypoints.splice(index, 1);
        
        // Remove marker from map
        const markerIndex = this.waypointMarkers.findIndex(m => m.waypointSequence === sequence);
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
     * @param {number} sequence - Sequence of waypoint to replace
     * @param {number} newLat - New latitude
     * @param {number} newLng - New longitude
     * @param {object} locationInfo - Optional pre-fetched location information
     */
    replaceWaypointLocation: function(sequence, newLat, newLng, locationInfo) {
        const waypoint = this.waypoints.find(w => w.sequence === sequence);
        if (!waypoint) return;
        
        waypoint.lat = window.TripWeather.Utils.Helpers.formatCoordinate(newLat);
        waypoint.lng = window.TripWeather.Utils.Helpers.formatCoordinate(newLng);
        waypoint.weather = null;
        
        // Update location info if provided
        if (locationInfo) {
            waypoint.locationName = locationInfo.locationName;
            // Store all timezone information
            waypoint.timezoneName = locationInfo.timezoneName || '';
            waypoint.timezoneStdOffset = locationInfo.timezoneStdOffset || '';
            waypoint.timezoneDstOffset = locationInfo.timezoneDstOffset || '';
            waypoint.timezoneStdAbbr = locationInfo.timezoneStdAbbr || '';
            waypoint.timezoneDstAbbr = locationInfo.timezoneDstAbbr || '';
        } else {
            waypoint.locationName = '';
            // Clear all timezone information
            waypoint.timezoneName = '';
            waypoint.timezoneStdOffset = '';
            waypoint.timezoneDstOffset = '';
            waypoint.timezoneStdAbbr = '';
            waypoint.timezoneDstAbbr = '';
        }
        
        // Update marker position using correct index
        const index = this.waypoints.findIndex(w => w.sequence === sequence);
        if (index !== -1) {
            const marker = this.waypointMarkers.find(m => m.waypointSequence === sequence);
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
     * @param {number} sequence - Waypoint sequence
     * @param {string} field - Field name to update
     * @param {*} value - New field value
     */
    updateWaypointField: function(sequence, field, value) {
        const waypoint = this.waypoints.find(w => w.sequence === sequence);
        if (!waypoint) return;
        
        if (field === 'date' || field === 'time' || field === 'locationName') {
            waypoint[field] = value;
            
            // Fetch weather when date and time are set
            if ((field === 'date' || field === 'time') && waypoint.date && waypoint.time) {
                this.fetchWeatherForWaypoint(waypoint);
            }
        }
        
        // Update UI - specifically update the table to show the correct timezone abbreviation
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
    },

    /**
     * Update waypoint duration
     * @param {number} sequence - Waypoint sequence
     * @param {string} inputValue - Duration input value
     */
    updateWaypointDuration: function(sequence, inputValue) {
        const waypoint = this.waypoints.find(w => w.sequence === sequence);
        if (!waypoint) return;
        
        const validation = window.TripWeather.Utils.Duration.validateDurationInput(inputValue);
        
        // Update input field with corrected value if needed
        const inputElement = document.querySelector(`tr[data-waypoint-sequence="${sequence}"] .duration-input`);
        if (inputElement && !validation.isValid) {
            inputElement.value = validation.correctedValue;
        }
        
        // Update waypoint duration
        waypoint.duration = validation.minutes;
        
        // Update marker popup using correct lookup
        const marker = this.waypointMarkers.find(m => m.waypointSequence === sequence);
        if (marker) {
            const index = this.waypoints.findIndex(w => w.sequence === sequence);
            window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
        }
    },

    /**
     * Increment waypoint duration
     * @param {number} sequence - Waypoint sequence
     * @param {number} incrementMinutes - Minutes to increment
     */
    incrementWaypointDuration: function(sequence, incrementMinutes) {
        const waypoint = this.waypoints.find(w => w.sequence === sequence);
        if (!waypoint) return;
        
        const currentFormatted = window.TripWeather.Utils.Duration.formatDuration(waypoint.duration);
        const newFormatted = window.TripWeather.Utils.Duration.incrementDuration(currentFormatted, incrementMinutes);
        const newMinutes = window.TripWeather.Utils.Duration.parseDuration(newFormatted);
        
        // Update waypoint
        waypoint.duration = newMinutes;
        
        // Update input field
        const inputElement = document.querySelector(`tr[data-waypoint-sequence="${sequence}"] .duration-input`);
        if (inputElement) {
            inputElement.value = newFormatted;
        }
        
        // Update marker popup using correct lookup
        const marker = this.waypointMarkers.find(m => m.waypointSequence === sequence);
        if (marker) {
            const index = this.waypoints.findIndex(w => w.sequence === sequence);
            window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
        }
    },

    /**
     * Reorder waypoints
     * @param {number} draggedSequence - Sequence of waypoint being dragged
     * @param {number} targetSequence - Sequence of target waypoint
     */
    reorderWaypoints: function(draggedSequence, targetSequence) {
        const draggedIndex = this.waypoints.findIndex(w => w.sequence === draggedSequence);
        const targetIndex = this.waypoints.findIndex(w => w.sequence === targetSequence);
        
        if (draggedIndex === -1 || targetIndex === -1) return;
        
        // Don't do anything if dragging to same position
        if (draggedIndex === targetIndex) return;
        
        const [removed] = this.waypoints.splice(draggedIndex, 1);
        
        // If we removed an item before target, target index shifts left by 1
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
     * @param {number} draggedSequence - Sequence of waypoint to move
     */
    moveToEnd: function(draggedSequence) {
        const draggedIndex = this.waypoints.findIndex(w => w.sequence === draggedSequence);
        
        if (draggedIndex === -1) return;
        
        // Don't do anything if already at end
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
        
        // Recreate all markers with new order numbers and ensure synchronization
        if (window.TripWeather.Managers.WaypointRenderer) {
            this.waypoints.forEach((waypoint, index) => {
                const marker = window.TripWeather.Managers.WaypointRenderer.addMarkerToMap(waypoint, index + 1);
                // Ensure the marker array stays in sync with waypoints array
                this.waypointMarkers.push(marker);
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
                // Store all timezone information
                waypoint.timezoneName = locationInfo.timezoneName || '';
                waypoint.timezoneStdOffset = locationInfo.timezoneStdOffset || '';
                waypoint.timezoneDstOffset = locationInfo.timezoneDstOffset || '';
                waypoint.timezoneStdAbbr = locationInfo.timezoneStdAbbr || '';
                waypoint.timezoneDstAbbr = locationInfo.timezoneDstAbbr || '';
                
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
                // Extract timezone information directly from response
                if (data && data.features && data.features.length > 0) {
                    const properties = data.features[0].properties;
                    const timezone = properties.timezone;
                    
                    if (timezone) {
                        // Store all timezone information from API
                        waypoint.timezoneName = timezone.name || '';
                        waypoint.timezoneStdOffset = timezone.offset_STD || '';
                        waypoint.timezoneDstOffset = timezone.offset_DST || '';
                        waypoint.timezoneStdAbbr = timezone.abbreviation_STD || '';
                        waypoint.timezoneDstAbbr = timezone.abbreviation_DST || '';
                        
                        // Update the current timezone abbreviation based on date
                        const targetDate = waypoint.date && waypoint.time ? `${waypoint.date} ${waypoint.time}` : null;
                        if (targetDate && waypoint.timezoneName) {
                            // Use the appropriate abbreviation based on DST
                            const isDst = window.TripWeather.Utils.Timezone.isDaylightSavingTimeForDate(new Date(targetDate), waypoint);
                            waypoint.timezone = isDst ? waypoint.timezoneDstAbbr : waypoint.timezoneStdAbbr;
                        } else {
                            // Default to standard time if no date provided
                            waypoint.timezone = waypoint.timezoneStdAbbr;
                        }
                        
                        // Update table to show new timezone
                        if (window.TripWeather.Managers.WaypointRenderer) {
                            window.TripWeather.Managers.WaypointRenderer.updateTable();
                        }
                        
                        // Update marker popup to show new timezone
                        const marker = window.TripWeather.Managers.Waypoint.waypointMarkers.find(m => m.waypointSequence === waypoint.sequence);
                        if (marker) {
                            const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.sequence === waypoint.sequence);
                            window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
                        }
                        
                        console.log(`Timezone rechecked for waypoint ${waypoint.sequence}: ${targetDate || 'current'}`);
                    } else {
                        console.warn('No timezone information found in response for waypoint', waypoint.sequence);
                    }
                } else {
                    console.warn('Invalid response data for timezone recheck');
                }
            }.bind(this))
            .catch(function(error) {
                console.warn('Failed to recheck timezone:', error);
                // Don't leave timezone blank on error, keep existing value
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
     * Get waypoint by sequence
     * @param {number} sequence - Waypoint sequence
     * @returns {object|null} - Waypoint object or null if not found
     */
    getWaypoint: function(sequence) {
        return this.waypoints.find(w => w.sequence === sequence) || null;
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
     * @param {number} sequence - Waypoint sequence
     */
    centerOnWaypoint: function(sequence) {
        const waypoint = this.getWaypoint(sequence);
        if (!waypoint) return;
        
        window.TripWeather.Managers.Map.centerOn(waypoint.lat, waypoint.lng);
        
        const marker = this.waypointMarkers.find(m => m.waypointSequence === sequence);
        if (marker) {
            marker.openPopup();
        }
        
        // Highlight table row
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.highlightTableRow(sequence);
        }
    },

    /**
     * Start waypoint replacement process
     * @param {number} sequence - Waypoint sequence to replace
     */
    selectNewLocationForWaypoint: function(sequence) {
        this.replacingWaypointSequence = sequence;
        window.TripWeather.Managers.Map.setCursor('crosshair');
        window.TripWeather.Utils.Helpers.showToast('Click on the map to select a new location for this waypoint.', 'info');
    },

    /**
     * Get replacing waypoint sequence
     * @returns {number|null} - Current replacing waypoint sequence or null
     */
    getReplacingWaypointSequence: function() {
        return this.replacingWaypointSequence;
    },

    /**
     * Set replacing waypoint sequence
     * @param {number|null} sequence - Waypoint sequence or null to clear
     */
    setReplacingWaypointSequence: function(sequence) {
        this.replacingWaypointSequence = sequence;
    },

    /**
     * Get last waypoint number
     * @returns {number} - Last waypoint sequence or 0 if no waypoints
     */
    getLastWaypointNumber: function() {
        return this.waypoints.length;
    },

    /**
     * Clear all waypoints
     */
    clearAllWaypoints: function() {
        // Remove all markers from map
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            this.waypointMarkers.forEach(marker => map.removeLayer(marker));
        }
        
        // Clear all data
        this.waypoints = [];
        this.waypointMarkers = [];
        this.nextSequence = 1;
        
        // Update UI
        if (window.TripWeather.Managers.WaypointRenderer) {
            window.TripWeather.Managers.WaypointRenderer.updateTable();
        }
        
        // Update route button state
        if (window.TripWeather.Managers.Route) {
            window.TripWeather.Managers.Route.updateButtonState();
        }
    },

    /**
     * Clear waypoint entity identifiers so they are treated as new when persisted
     */
    clearWaypointIds: function() {
        this.waypoints.forEach(function(waypoint) {
            waypoint.uuid = null;
        });
    }
};
