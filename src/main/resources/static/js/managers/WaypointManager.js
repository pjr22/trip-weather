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
     * @param {number} alt - Elevation in meters
     */
    Waypoint: function(sequence, uuid, lat, lng, alt) {
        this.sequence = sequence;
        this.uuid = uuid; // null for new waypoints, will be set by backend
        this.lat = window.TripWeather.Utils.Helpers.formatCoordinate(lat);
        this.lng = window.TripWeather.Utils.Helpers.formatCoordinate(lng);
        this.alt = alt;
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
        this.distance = 0;             // Distance from previous waypoint in miles (0 for first waypoint)
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
        const self = this;
        
        // Validate location using snap endpoint before proceeding
        window.TripWeather.Services.Location.snapToLocation(e.latlng.lat, e.latlng.lng)
            .then(function(isRouteable) {
                if (!isRouteable) {
                    // Location is not routeable, show error and don't add waypoint
                    window.TripWeather.Utils.Helpers.showToast('That location is not routeable. Select a different location.', 'error');
                    return;
                }
                
                // Location is valid, proceed with waypoint addition/replacement
                if (self.replacingWaypointSequence !== null) {
                    self.replaceWaypointLocation(self.replacingWaypointSequence, e.latlng.lat, e.latlng.lng, 0);
                    self.replacingWaypointSequence = null;
                    window.TripWeather.Managers.Map.setCursor('');
                } else {
                    self.addWaypoint(e.latlng.lat, e.latlng.lng, 0);
                }
            })
            .catch(function(error) {
                console.error('Error validating location:', error);
                // If there's an error with validation, show error message
                window.TripWeather.Utils.Helpers.showToast('Error validating location. Please try again.', 'error');
            });
    },

    /**
     * Add a new waypoint at specified coordinates
     * @param {number} lat - Latitude coordinate
     * @param {number} lng - Longitude coordinate
     * @param {number} alt - altitude
     * @param {object} locationInfo - Optional pre-fetched location information
     * @param {object} existingWaypoint - Optional existing waypoint object to load
     * @param {boolean} skipValidation - Skip validation (true for search results/user location, false for map clicks)
     * @returns {object} - Created waypoint
     */
    addWaypoint: function(lat, lng, alt, locationInfo, existingWaypoint, skipValidation) {
        const self = this;
        
        // If validation is not skipped, validate location using snap endpoint
        if (!skipValidation) {
            return window.TripWeather.Services.Location.snapToLocation(lat, lng)
                .then(function(isRouteable) {
                    if (!isRouteable) {
                        // Location is not routeable, show error and don't add waypoint
                        window.TripWeather.Utils.Helpers.showToast('That location is not routeable. Select a different location.', 'error');
                        return null;
                    }
                    
                    // Location is valid, proceed with waypoint addition
                    return self.performWaypointAddition(lat, lng, alt, locationInfo, existingWaypoint);
                })
                .catch(function(error) {
                    console.error('Error validating location:', error);
                    // If there's an error with validation, show error message
                    window.TripWeather.Utils.Helpers.showToast('Error validating location. Please try again.', 'error');
                    return null;
                });
        } else {
            // Skip validation and proceed directly with addition
            return this.performWaypointAddition(lat, lng, alt, locationInfo, existingWaypoint);
        }
    },
    
    /**
     * Perform actual waypoint addition after validation
     * @param {number} lat - Latitude coordinate
     * @param {number} lng - Longitude coordinate
     * @param {number} alt - altitude
     * @param {object} locationInfo - Optional pre-fetched location information
     * @param {object} existingWaypoint - Optional existing waypoint object to load
     * @returns {object} - Created waypoint
     */
    performWaypointAddition: function(lat, lng, alt, locationInfo, existingWaypoint) {
        let waypoint;
        if (existingWaypoint) {
            // Use existing waypoint data for loading routes
            waypoint = new this.Waypoint(
                existingWaypoint.sequence || this.nextSequence++,
                existingWaypoint.uuid,
                lat,
                lng,
                alt
            );
            waypoint.locationName = existingWaypoint.locationName || '';
            waypoint.date = existingWaypoint.date || '';
            waypoint.time = existingWaypoint.time || '';
            waypoint.duration = existingWaypoint.duration || 0;
            waypoint.distance = existingWaypoint.distance || 0;
            
            // Copy timezone information if it exists
            if (existingWaypoint.timezoneName) {
                waypoint.timezoneName = existingWaypoint.timezoneName;
                waypoint.timezoneStdOffset = existingWaypoint.timezoneStdOffset || '';
                waypoint.timezoneDstOffset = existingWaypoint.timezoneDstOffset || '';
                waypoint.timezoneStdAbbr = existingWaypoint.timezoneStdAbbr || '';
                waypoint.timezoneDstAbbr = existingWaypoint.timezoneDstAbbr || '';
            } else if (existingWaypoint.timezone) {
                // Handle case where only timezone field is set (from loaded routes)
                waypoint.timezoneName = existingWaypoint.timezone;
                // For loaded routes, we might not have detailed timezone info
                // Set default values that will be populated when needed
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
            waypoint = new this.Waypoint(this.nextSequence++, null, lat, lng, alt);
            
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
        
        // Fetch location info if not provided and not loading from existing waypoint
        // For loaded routes, preserve the custom location name from the waypoint data
        if (!locationInfo && !existingWaypoint) {
            this.fetchLocationInfo(waypoint);
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
     * @param {number} newAlt - New altitude
     * @param {object} locationInfo - Optional pre-fetched location information
     * @param {boolean} skipValidation - Skip validation (true for search results, false for map clicks)
     */
    replaceWaypointLocation: function(sequence, newLat, newLng, newAlt, locationInfo, skipValidation) {
        const self = this;
        const waypoint = this.waypoints.find(w => w.sequence === sequence);
        if (!waypoint) return;
        
        // If validation is not skipped, validate location using snap endpoint
        if (!skipValidation) {
            window.TripWeather.Services.Location.snapToLocation(newLat, newLng)
                .then(function(isRouteable) {
                    if (!isRouteable) {
                        // Location is not routeable, show error and don't replace waypoint
                        window.TripWeather.Utils.Helpers.showToast('That location is not routeable. Select a different location.', 'error');
                        return;
                    }
                    
                    // Location is valid, proceed with waypoint replacement
                    self.performWaypointReplacement(sequence, newLat, newLng, newAlt, locationInfo);
                })
                .catch(function(error) {
                    console.error('Error validating location:', error);
                    // If there's an error with validation, show error message
                    window.TripWeather.Utils.Helpers.showToast('Error validating location. Please try again.', 'error');
                });
        } else {
            // Skip validation and proceed directly with replacement
            this.performWaypointReplacement(sequence, newLat, newLng, newAlt, locationInfo);
        }
    },
    
    /**
     * Perform the actual waypoint replacement after validation
     * @param {number} sequence - Sequence of waypoint to replace
     * @param {number} newLat - New latitude
     * @param {number} newLng - New longitude
     * @param {number} newAlt - New altitude
     * @param {object} locationInfo - Optional pre-fetched location information
     */
    performWaypointReplacement: function(sequence, newLat, newLng, newAlt, locationInfo) {
        const waypoint = this.waypoints.find(w => w.sequence === sequence);
        if (!waypoint) return;
        
        waypoint.lat = window.TripWeather.Utils.Helpers.formatCoordinate(newLat);
        waypoint.lng = window.TripWeather.Utils.Helpers.formatCoordinate(newLng);
        waypoint.alt = newAlt;
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
            this.fetchLocationInfo(waypoint);
        }
        
        // Update layer time if needed (timezone might have changed)
        if (window.TripWeather.Managers.Layer) {
            window.TripWeather.Managers.Layer.updateLayerTime(waypoint);
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
            
            // Update layer time if date or time changed
            if ((field === 'date' || field === 'time') && window.TripWeather.Managers.Layer) {
                window.TripWeather.Managers.Layer.updateLayerTime(waypoint);
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
    fetchLocationInfo: function(waypoint) {

        return window.TripWeather.Services.Location.getLocationInfo(waypoint.lat, waypoint.lng)
            .then(function(locationInfo) {
                waypoint.locationName = locationInfo.locationName;
                waypoint.alt = locationInfo.elevation;
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
                    
                    // Open popup for the newly added waypoint after location info is retrieved
                    window.TripWeather.Managers.WaypointRenderer.openWaypointPopup(waypoint.sequence);
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
                            // Store the current timezone abbreviation in a separate field for display
                            waypoint.currentTimezoneAbbr = isDst ? waypoint.timezoneDstAbbr : waypoint.timezoneStdAbbr;
                        } else {
                            // Default to standard time if no date provided
                            waypoint.currentTimezoneAbbr = waypoint.timezoneStdAbbr;
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
