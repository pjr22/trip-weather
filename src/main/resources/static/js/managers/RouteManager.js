/**
 * Route Manager
 * Handles route calculation, display, and management
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Route = {
    
    // Route state
    routePolylines: [],
    routeLabels: [],
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
            window.TripWeather.Managers.UI.showToast('Please add at least 2 waypoints to calculate a route.', 'warning');
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
                window.TripWeather.Managers.UI.showToast('Failed to calculate route: ' + error.message, 'error');
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

        // Update waypoint arrival times, durations, distances, and timezones if provided
        if (routeData.waypoints && routeData.waypoints.length > 0) {
            this.updateWaypointsWithRouteData(routeData.waypoints);
        }
        
        // Update waypoint distances if segments are provided
        if (routeData.segments && routeData.segments.length > 0) {
            this.updateWaypointDistances(routeData.segments);
            // Add distance labels to route segments
            this.addDistanceLabels(routeData.segments, routeCoordinates);
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

        // Update route statistics
        this.updateRouteStats(routeData);
        
        // Notify EV Charging Station Manager
        if (window.TripWeather.Managers.EVChargingStation) {
            window.TripWeather.Managers.EVChargingStation.onRouteCalculated(routeData);
        }
    },

    /**
     * Update route statistics display
     * @param {object} routeData - Route data from API
     */
    updateRouteStats: function(routeData) {
        const statsContainer = document.getElementById('route-stats');
        if (!statsContainer) return;

        if (!routeData) {
            statsContainer.style.display = 'none';
            return;
        }

        statsContainer.style.display = 'flex';

        // 1. Distance
        const distanceEl = document.getElementById('route-total-distance');
        if (distanceEl) {
            const distanceInMiles = (routeData.distance || 0) * 0.0006213712;
            distanceEl.textContent = this.formatDistance(distanceInMiles);
        }

        // 2. Drive Time
        const timeEl = document.getElementById('route-total-time');
        if (timeEl) {
            const durationMinutes = Math.round((routeData.duration || 0) / 60);
            timeEl.textContent = window.TripWeather.Utils.Duration.formatDuration(durationMinutes);
        }

        // 3. Elevation
        const elevationContainer = document.getElementById('route-elevation');
        if (elevationContainer && routeData.geometry && routeData.geometry.length > 0) {
            let totalIncrease = 0;
            let totalDecrease = 0;
            let hasElevation = false;

            // Iterate through geometry coordinates [lon, lat, ele]
            // Note: OpenRouteService returns [lon, lat, ele] (GeoJSON format)
            const coordinates = routeData.geometry;
            
            for (let i = 0; i < coordinates.length; i++) {
                // Check if elevation data exists (3rd element)
                if (coordinates[i].length > 2) {
                    hasElevation = true;
                    const ele = coordinates[i][2];
                    
                    if (i > 0 && coordinates[i-1].length > 2) {
                        const prevEle = coordinates[i-1][2];
                        const diff = ele - prevEle;
                        
                        if (diff > 0) {
                            totalIncrease += diff;
                        } else {
                            totalDecrease += Math.abs(diff);
                        }
                    }
                }
            }
            
            // If we found elevation data
            if (hasElevation) {
                // Convert to feet (1 meter = 3.28084 feet)
                const toFeet = (meters) => Math.round(meters * 3.28084);
                
                const increaseFt = toFeet(totalIncrease);
                const decreaseFt = toFeet(totalDecrease);
                const netChangeFt = increaseFt - decreaseFt;
                const netSign = netChangeFt >= 0 ? '+' : '';
                
                elevationContainer.innerHTML = `
                    <span class="elevation-up" title="Total Ascent">↑ ${increaseFt.toLocaleString()} ft</span>
                    <span class="elevation-down" title="Total Descent">↓ ${decreaseFt.toLocaleString()} ft</span>
                    <span class="elevation-net" title="Net Elevation Change">(${netSign}${netChangeFt.toLocaleString()} ft)</span>
                `;
            } else {
                elevationContainer.innerHTML = '<span style="color: #999;">No elevation data</span>';
            }
        }
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
                        waypoint.currentTimezoneAbbr = window.TripWeather.Utils.Timezone.getTimezoneAbbrFromWaypoint(waypoint, dateTimeStr);
                    }
                }
            }
        });
        
        // Update table to show new arrival times, durations, timezones, and distances
        window.TripWeather.Managers.WaypointRenderer.updateTable();
    },
    
    /**
     * Update waypoint distances from route segments
     * @param {Array} segments - Route segments from API response
     */
    updateWaypointDistances: function(segments) {
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        
        // First waypoint always has distance 0
        if (waypoints.length > 0) {
            waypoints[0].distance = 0;
        }
        
        // Update distances for remaining waypoints based on segment distances
        for (let i = 0; i < segments.length && i < waypoints.length - 1; i++) {
            const segment = segments[i];
            if (segment.distance !== undefined && segment.distance !== null) {
                // Convert distance from meters to miles (1 meter = 0.0006213712 miles)
                const distanceInMiles = segment.distance * 0.0006213712;
                waypoints[i + 1].distance = distanceInMiles;
            }
        }
        
        // Update table to show new distances
        window.TripWeather.Managers.WaypointRenderer.updateTable();
    },

    /**
     * Format distance for display
     * @param {number} distanceInMiles - Distance in miles
     * @returns {string} - Formatted distance string
     */
    formatDistance: function(distanceInMiles) {
        if (distanceInMiles >= 0.1) {
            // Display in miles with 1 decimal place
            return distanceInMiles.toFixed(1) + ' mi';
        } else {
            // Display in feet (1 mile = 5280 feet)
            const distanceInFeet = distanceInMiles * 5280;
            return Math.round(distanceInFeet) + ' ft';
        }
    },

    /**
     * Calculate midpoint of a route segment for label placement
     * @param {Array} segmentCoordinates - Array of coordinates for the segment
     * @returns {Array} - Midpoint coordinates [lat, lng]
     */
    calculateSegmentMidpoint: function(segmentCoordinates) {
        if (!segmentCoordinates || segmentCoordinates.length < 2) {
            return null;
        }
        
        // Calculate the actual midpoint along the path by measuring total distance
        // and finding the point at half the total distance
        const totalDistance = this.calculatePathDistance(segmentCoordinates);
        const halfDistance = totalDistance / 2;
        
        return this.findPointAtDistance(segmentCoordinates, halfDistance);
    },

    /**
     * Calculate the total distance along a path
     * @param {Array} coordinates - Array of [lat, lng] coordinates
     * @returns {number} - Total distance in same units as coordinates
     */
    calculatePathDistance: function(coordinates) {
        if (!coordinates || coordinates.length < 2) {
            return 0;
        }
        
        let totalDistance = 0;
        for (let i = 0; i < coordinates.length - 1; i++) {
            totalDistance += this.calculateDistance(coordinates[i], coordinates[i + 1]);
        }
        
        return totalDistance;
    },

    /**
     * Calculate distance between two points
     * @param {Array} point1 - First point [lat, lng]
     * @param {Array} point2 - Second point [lat, lng]
     * @returns {number} - Distance between points
     */
    calculateDistance: function(point1, point2) {
        const latDiff = point2[0] - point1[0];
        const lngDiff = point2[1] - point1[1];
        return Math.sqrt(latDiff * latDiff + lngDiff * lngDiff);
    },

    /**
     * Find the point along a path at a specific distance from the start
     * @param {Array} coordinates - Array of [lat, lng] coordinates
     * @param {number} targetDistance - Distance from start to find point
     * @returns {Array} - Point at target distance [lat, lng]
     */
    findPointAtDistance: function(coordinates, targetDistance) {
        if (!coordinates || coordinates.length < 2) {
            return null;
        }
        
        let accumulatedDistance = 0;
        
        for (let i = 0; i < coordinates.length - 1; i++) {
            const startPoint = coordinates[i];
            const endPoint = coordinates[i + 1];
            const segmentDistance = this.calculateDistance(startPoint, endPoint);
            
            // If the target distance is within this segment
            if (accumulatedDistance + segmentDistance >= targetDistance) {
                const remainingDistance = targetDistance - accumulatedDistance;
                const ratio = remainingDistance / segmentDistance;
                
                // Interpolate between start and end points
                return [
                    startPoint[0] + (endPoint[0] - startPoint[0]) * ratio,
                    startPoint[1] + (endPoint[1] - startPoint[1]) * ratio
                ];
            }
            
            accumulatedDistance += segmentDistance;
        }
        
        // If we've gone through all segments, return the last point
        return coordinates[coordinates.length - 1];
    },

    /**
     * Calculate a perpendicular offset point from a line segment
     * @param {Array} startPoint - Start point of segment [lat, lng]
     * @param {Array} endPoint - End point of segment [lat, lng]
     * @param {Array} midPoint - Midpoint of segment [lat, lng]
     * @param {number} offsetDistance - Distance to offset (positive for right, negative for left)
     * @returns {Array} - Offset point [lat, lng]
     */
    calculateOffsetPoint: function(startPoint, endPoint, midPoint, offsetDistance) {
        // Calculate the direction vector of the segment
        const dx = endPoint[1] - startPoint[1];
        const dy = endPoint[0] - startPoint[0];
        
        // Normalize the direction vector
        const length = Math.sqrt(dx * dx + dy * dy);
        if (length === 0) return midPoint;
        
        const normalizedDx = dx / length;
        const normalizedDy = dy / length;
        
        // Calculate perpendicular vector (rotate 90 degrees)
        const perpDx = -normalizedDy;
        const perpDy = normalizedDx;
        
        // Apply offset with a minimum threshold to ensure visibility
        const adjustedOffset = Math.abs(offsetDistance) < 0.001 ?
            (offsetDistance > 0 ? 0.001 : -0.001) : offsetDistance;
        
        return [
            midPoint[0] + perpDy * adjustedOffset,
            midPoint[1] + perpDx * adjustedOffset
        ];
    },

    /**
     * Add distance labels to route segments
     * @param {Array} segments - Route segments from API response
     * @param {Array} routeCoordinates - All route coordinates
     */
    addDistanceLabels: function(segments, routeCoordinates) {
        const map = window.TripWeather.Managers.Map.getMap();
        if (!map || !segments || segments.length === 0 || !routeCoordinates || routeCoordinates.length === 0) {
            return;
        }

        // Clear existing labels
        this.clearDistanceLabels();

        // For each segment, create a label
        segments.forEach((segment, index) => {
            if (segment.distance !== undefined && segment.distance !== null) {
                // Convert distance from meters to miles
                const distanceInMiles = segment.distance * 0.0006213712;
                const formattedDistance = this.formatDistance(distanceInMiles);
                
                // Find the midpoint for this segment
                // Use the waypoints data to get more accurate segment coordinates
                const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
                
                let segmentCoords = [];
                
                // If we have waypoints, use them to determine segment boundaries
                if (waypoints && waypoints.length > index + 1) {
                    const startWaypoint = waypoints[index];
                    const endWaypoint = waypoints[index + 1];
                    
                    // Find the coordinates closest to each waypoint
                    const startIndex = this.findClosestCoordinateIndex(routeCoordinates, [startWaypoint.lat, startWaypoint.lng]);
                    const endIndex = this.findClosestCoordinateIndex(routeCoordinates, [endWaypoint.lat, endWaypoint.lng]);
                    
                    if (startIndex !== -1 && endIndex !== -1 && startIndex < endIndex) {
                        segmentCoords = routeCoordinates.slice(startIndex, endIndex + 1);
                    }
                }
                
                // Fallback: divide route coordinates evenly among segments
                if (segmentCoords.length === 0) {
                    const coordsPerSegment = Math.max(1, Math.floor(routeCoordinates.length / segments.length));
                    const segmentStartIndex = index * coordsPerSegment;
                    const segmentEndIndex = Math.min((index + 1) * coordsPerSegment, routeCoordinates.length - 1);
                    segmentCoords = routeCoordinates.slice(segmentStartIndex, segmentEndIndex + 1);
                }
                
                // Calculate the actual midpoint along the path
                const midpoint = this.calculateSegmentMidpoint(segmentCoords);
                
                if (midpoint && segmentCoords.length >= 2) {
                    // Format duration (segment.duration is in seconds)
                    const formattedDuration = segment.duration ? this.formatSegmentDuration(segment.duration) : '';
                    
                    // Create a custom icon for the distance label
                    const labelIcon = L.divIcon({
                        className: 'route-distance-label',
                        html: `<div class="distance-label-content">${formattedDistance}<br>${formattedDuration}</div>`,
                        iconSize: [60, 30],
                        iconAnchor: [30, 15] // Center the icon on the point
                    });
                    
                    // Create and add the marker directly on the route line at the midpoint
                    const labelMarker = L.marker(midpoint, { icon: labelIcon }).addTo(map);
                    this.routeLabels.push(labelMarker);
                }
            }
        });
    },

    /**
     * Find the index of the coordinate closest to the target point
     * @param {Array} coordinates - Array of [lat, lng] coordinates
     * @param {Array} targetPoint - Target point as [lat, lng]
     * @returns {number} - Index of the closest coordinate
     */
    findClosestCoordinateIndex: function(coordinates, targetPoint) {
        if (!coordinates || coordinates.length === 0 || !targetPoint || targetPoint.length < 2) {
            return -1;
        }
        
        let minDistance = Infinity;
        let closestIndex = 0;
        
        coordinates.forEach((coord, index) => {
            // Calculate distance using Haversine formula approximation
            const latDiff = coord[0] - targetPoint[0];
            const lngDiff = coord[1] - targetPoint[1];
            const distance = Math.sqrt(latDiff * latDiff + lngDiff * lngDiff);
            
            if (distance < minDistance) {
                minDistance = distance;
                closestIndex = index;
            }
        });
        
        return closestIndex;
    },

    /**
     * Format segment duration for display
     * @param {number} durationInSeconds - Duration in seconds
     * @returns {string} - Formatted duration string (e.g., "01h10m")
     */
    formatSegmentDuration: function(durationInSeconds) {
        if (!durationInSeconds || durationInSeconds <= 0) {
            return '';
        }
        
        // Convert seconds to minutes
        const durationInMinutes = Math.round(durationInSeconds / 60);
        
        // Use DurationUtils to format the duration
        return window.TripWeather.Utils.Duration.formatDuration(durationInMinutes);
    },

    /**
     * Clear distance labels from map
     */
    clearDistanceLabels: function() {
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            this.routeLabels.forEach(function(label) {
                map.removeLayer(label);
            });
        }
        this.routeLabels = [];
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
        
        // Clear distance labels
        this.clearDistanceLabels();
        
        this.currentRoute = null;

        // Reset button text
        window.TripWeather.Managers.UI.updateRouteButtonText('🛣️ Calculate Route');

        // Clear route statistics
        this.updateRouteStats(null);
        
        // Notify EV Charging Station Manager
        if (window.TripWeather.Managers.EVChargingStation) {
            window.TripWeather.Managers.EVChargingStation.onRouteCleared();
        }
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
