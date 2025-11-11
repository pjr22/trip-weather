/**
 * Route Manager
 * Handles route calculation, display, and management
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Route = {
    
    // Route state
    routePolylines: [],
    currentRoute: null,
    
    /**
     * Initialize route manager
     */
    initialize: function() {
        const calculateRouteBtn = document.getElementById('calculate-route-btn');
        if (calculateRouteBtn) {
            calculateRouteBtn.addEventListener('click', this.calculateRoute.bind(this));
        }
        
        this.updateButtonState();
    },

    /**
     * Show route loading overlay
     */
    showLoading: function() {
        window.TripWeather.Managers.UI.showLoading('route-loading-overlay');
    },

    /**
     * Hide route loading overlay
     */
    hideLoading: function() {
        window.TripWeather.Managers.UI.hideLoading('route-loading-overlay');
    },

    /**
     * Update route button state based on waypoint count
     */
    updateButtonState: function() {
        const waypointCount = window.TripWeather.Managers.Waypoint.getWaypointCount();
        window.TripWeather.Managers.UI.updateRouteButtonState(waypointCount);
    },

    /**
     * Calculate route between waypoints
     */
    calculateRoute: function() {
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        
        if (waypoints.length < 2) {
            window.TripWeather.Managers.UI.showAlert('Please add at least 2 waypoints to calculate a route.');
            return;
        }

        this.showLoading();

        // Prepare waypoints for API request with date, time, duration, and timezone name
        const waypointData = waypoints.map(function(waypoint) {
            return {
                latitude: parseFloat(waypoint.lat),
                longitude: parseFloat(waypoint.lng),
                name: waypoint.locationName || `Waypoint ${waypoint.sequence}`,
                date: waypoint.date || '',
                time: waypoint.time || '',
                duration: waypoint.duration || 0,
                timezoneName: waypoint.timezoneName || ''
            };
        });

        window.TripWeather.Utils.Helpers.httpPost('/api/route/calculate', waypointData)
            .then(function(routeData) {
                if (routeData && routeData.geometry && routeData.geometry.length > 0) {
                    window.TripWeather.Managers.Route.displayRoute(routeData);
                    window.TripWeather.Managers.Route.currentRoute = routeData;
                } else {
                    throw new Error('No route geometry returned from API');
                }
            })
            .catch(function(error) {
                console.error('Route calculation error:', error);
                window.TripWeather.Managers.UI.showAlert('Failed to calculate route: ' + error.message);
                window.TripWeather.Managers.Route.clearRoute();
            })
            .finally(function() {
                window.TripWeather.Managers.Route.hideLoading();
            });
    },

    /**
     * Display route on map
     * @param {object} routeData - Route data from API
     */
    displayRoute: function(routeData) {
        // Clear existing route
        this.clearRoute();

        if (!routeData.geometry || routeData.geometry.length === 0) {
            console.warn('No route geometry to display');
            return;
        }

        // Convert geometry coordinates for Leaflet (Leaflet expects [lat, lng])
        const routeCoordinates = routeData.geometry.map(function(coord) {
            return [coord[1], coord[0]];
        });

        // Create polyline for route - changed color from green (#27ae60) to blue (#0066cc)
        const map = window.TripWeather.Managers.Map.getMap();
        const routePolyline = L.polyline(routeCoordinates, {
            color: '#0066cc',
            weight: 4,
            opacity: 0.8,
            smoothFactor: 1
        }).addTo(map);

        this.routePolylines.push(routePolyline);

        // Fit map to show entire route
        window.TripWeather.Managers.Map.fitBounds(routeCoordinates, { padding: [50, 50] });

        // Update waypoint arrival times, durations, and timezones if provided
        if (routeData.waypoints && routeData.waypoints.length > 0) {
            this.updateWaypointsWithRouteData(routeData.waypoints);
        }
        
        // Fetch weather for all waypoints after route calculation
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        waypoints.forEach(waypoint => {
            if (waypoint.date && waypoint.time) {
                window.TripWeather.Managers.Waypoint.fetchWeatherForWaypoint(waypoint);
            }
        });

        // Log route information
        console.log('Route calculated:', {
            distance: routeData.distance ? (routeData.distance / 1000).toFixed(1) + ' km' : 'Unknown',
            duration: routeData.duration ? window.TripWeather.Utils.Duration.formatDuration(routeData.duration) : 'Unknown',
            waypoints: routeData.waypoints ? routeData.waypoints.length : 0
        });

        // Update button text to show route is active
        window.TripWeather.Managers.UI.updateRouteButtonText('🔄 Recalculate Route');
    },

    /**
     * Update waypoints with route data
     * @param {Array} routeWaypoints - Route waypoint data from API
     */
    updateWaypointsWithRouteData: function(routeWaypoints) {
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        
        routeWaypoints.forEach(function(routeWaypoint, index) {
            if (index < waypoints.length) {
                const waypoint = waypoints[index];
                
                // Update arrival time if provided by route calculation
                if (routeWaypoint.arrivalTime) {
                    const arrivalDateTime = routeWaypoint.arrivalTime.split(' ');
                    if (arrivalDateTime.length === 2) {
                        waypoint.date = arrivalDateTime[0];
                        waypoint.time = arrivalDateTime[1];
                        
                        // Fetch weather for updated arrival time
                        window.TripWeather.Managers.Waypoint.fetchWeatherForWaypoint(waypoint);
                    }
                }
                
                // Update duration if provided
                if (routeWaypoint.duration !== undefined && routeWaypoint.duration !== null) {
                    waypoint.duration = routeWaypoint.duration;
                }
                
                // Store timezone information if provided by route calculation
                const timezoneDataProvided = routeWaypoint.timezoneName ||
                    routeWaypoint.timezoneStdOffset ||
                    routeWaypoint.timezoneDstOffset ||
                    routeWaypoint.timezoneStdAbbr ||
                    routeWaypoint.timezoneDstAbbr;

                if (timezoneDataProvided) {
                    if (routeWaypoint.timezoneName) {
                        waypoint.timezoneName = routeWaypoint.timezoneName;
                    }
                    if (routeWaypoint.timezoneStdOffset) {
                        waypoint.timezoneStdOffset = routeWaypoint.timezoneStdOffset;
                    }
                    if (routeWaypoint.timezoneDstOffset) {
                        waypoint.timezoneDstOffset = routeWaypoint.timezoneDstOffset;
                    }
                    if (routeWaypoint.timezoneStdAbbr) {
                        waypoint.timezoneStdAbbr = routeWaypoint.timezoneStdAbbr;
                    }
                    if (routeWaypoint.timezoneDstAbbr) {
                        waypoint.timezoneDstAbbr = routeWaypoint.timezoneDstAbbr;
                    }

                    if (waypoint.date && waypoint.time && waypoint.timezoneName) {
                        const dateTimeStr = `${waypoint.date} ${waypoint.time}`;
                        waypoint.timezone = window.TripWeather.Utils.Timezone.getTimezoneAbbrFromWaypoint(waypoint, dateTimeStr);
                    }
                }
            }
        });
        
        // Update table to show new arrival times, durations, and timezones
        window.TripWeather.Managers.WaypointRenderer.updateTable();
    },

    /**
     * Clear route from map
     */
    clearRoute: function() {
        // Remove all route polylines from map
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            this.routePolylines.forEach(function(polyline) {
                map.removeLayer(polyline);
            });
        }
        this.routePolylines = [];
        this.currentRoute = null;

        // Reset button text
        window.TripWeather.Managers.UI.updateRouteButtonText('🛣️ Calculate Route');
    },

    /**
     * Clear route when waypoints are reordered or deleted, but not when adding waypoints at the end
     * @param {string} changeType - Type of change ('add', 'delete', 'reorder', 'modify')
     * @param {number} waypointIndex - Index of affected waypoint (optional)
     */
    clearRouteOnWaypointChange: function(changeType, waypointIndex) {
        if (!this.currentRoute) {
            return;
        }
        
        let shouldClearRoute = false;
        
        switch (changeType) {
            case 'delete':
                // Any deletion affects route
                shouldClearRoute = true;
                break;
                
            case 'reorder':
                // Any reordering affects route
                shouldClearRoute = true;
                break;
                
            case 'add':
                // Only clear if adding at a position that affects existing waypoints
                if (waypointIndex !== null && waypointIndex < window.TripWeather.Managers.Waypoint.getWaypointCount() - 1) {
                    shouldClearRoute = true;
                }
                break;
                
            case 'modify':
                // Any modification to existing waypoint affects route
                shouldClearRoute = true;
                break;
        }
        
        if (shouldClearRoute) {
            this.clearRoute();
            console.log('Route cleared due to waypoint changes');
        }
    },

    /**
     * Check if a change affects existing route
     * @param {string} changeType - Type of change
     * @param {number} waypointIndex - Index of affected waypoint
     * @returns {boolean} - Whether change affects route
     */
    changeAffectsExistingRoute: function(changeType, waypointIndex) {
        if (!this.currentRoute || waypointIndex === null) {
            return true;
        }
        
        const waypointsLength = Array.isArray(this.currentRoute.waypoints)
            ? this.currentRoute.waypoints.length
            : 0;

        // For additions, only affects route if not at the end
        if (changeType === 'add') {
            return waypointIndex < waypointsLength;
        }
        
        // For all other changes, it affects route
        return true;
    },

    /**
     * Get current route data
     * @returns {object|null} - Current route data or null
     */
    getCurrentRoute: function() {
        return this.currentRoute;
    },

    /**
     * Check if route is currently active
     * @returns {boolean} - Whether route is active
     */
    isRouteActive: function() {
        return this.currentRoute !== null;
    },

    /**
     * Get route statistics
     * @returns {object} - Route statistics
     */
    getRouteStats: function() {
        if (!this.currentRoute) {
            return {
                distance: 0,
                duration: 0,
                waypointCount: 0
            };
        }
        
        return {
            distance: this.currentRoute.distance || 0,
            duration: this.currentRoute.duration || 0,
            waypointCount: this.currentRoute.waypoints ? this.currentRoute.waypoints.length : 0
        };
    },

    /**
     * Export route data as JSON
     * @returns {string} - JSON string of route data
     */
    exportRoute: function() {
        if (!this.currentRoute) {
            return null;
        }
        
        const exportData = {
            route: this.currentRoute,
            waypoints: window.TripWeather.Managers.Waypoint.getAllWaypoints(),
            exportedAt: new Date().toISOString()
        };
        
        return JSON.stringify(exportData, null, 2);
    }
};
