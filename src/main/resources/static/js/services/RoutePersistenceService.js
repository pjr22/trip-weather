/**
 * Service for handling route persistence operations
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.RoutePersistence = {
    
    /**
     * Save a route to the server
     * @param {Object} routeData - Route data to save
     * @param {string} routeData.name - Route name
     * @param {Array} routeData.waypoints - Array of waypoint objects
     * @param {string} [routeData.userId] - Optional user ID (defaults to a default user)
     * @returns {Promise} Promise that resolves with saved route response and status
     */
    saveRoute: async function(routeData) {
        try {
            const response = await fetch('/api/routes', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(routeData)
            });
            
            let responseData;
            try {
                responseData = await response.json();
            } catch (jsonError) {
                // If response is not JSON, create a generic error response
                responseData = { 
                    error: response.statusText || 'Unknown error occurred',
                    status: response.status
                };
            }
            
            // Return both the data and status for proper handling
            return {
                data: responseData,
                status: response.status,
                ok: response.ok
            };
        } catch (error) {
            console.error('Error saving route:', error);
            throw error;
        }
    },
    
    /**
     * Load a route from the server by ID
     * @param {string} routeId - UUID of the route to load
     * @returns {Promise} Promise that resolves with the loaded route data
     */
    loadRoute: async function(routeId) {
        try {
            const response = await fetch(`/api/routes/${encodeURIComponent(routeId)}`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json',
                }
            });
            
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            
            return await response.json();
        } catch (error) {
            console.error('Error loading route:', error);
            throw error;
        }
    },
    
    /**
     * Convert waypoints from current format to DTO format
     * @param {Array} waypoints - Array of waypoint objects from the UI
     * @returns {Array} Array of waypoint DTOs
     */
    convertWaypointsToDto: function(waypoints) {
        return waypoints.map((waypoint, index) => ({
            id: waypoint.uuid || null, // Use uuid field for entity ID, null for new waypoints
            sequence: index + 1, // Sequence is based on array position
            date: waypoint.date || '',
            time: waypoint.time || '',
            timezone: waypoint.timezoneName || '', // Use timezoneName as the timezone field
            durationMin: waypoint.duration || 0, // Convert null/undefined to 0
            locationName: waypoint.locationName || '',
            latitude: parseFloat(waypoint.lat),
            longitude: parseFloat(waypoint.lng),
            routeId: null // Will be set by the backend
        }));
    },
    
    
    /**
     * Convert waypoint DTOs back to the UI format
     * @param {Array} waypointDtos - Array of waypoint DTOs from the server
     * @returns {Array} Array of waypoint objects for the UI
     */
    convertWaypointsFromDto: function(waypointDtos) {
        return waypointDtos.map(dto => {
            return {
                uuid: dto.id, // Store the entity UUID
                sequence: dto.sequence, // Store the sequence number
                date: dto.date || '', // Use separate date field
                time: dto.time || '', // Use separate time field
                timezoneName: dto.timezone || '', // Use timezone field as timezoneName
                duration: dto.durationMin || 0, // Convert null to 0
                locationName: dto.locationName || '',
                lat: dto.latitude,
                lng: dto.longitude
            };
        });
    },
    
    
    /**
     * Search for routes by name
     * @param {string} searchQuery - Route name to search for
     * @param {string} [username] - Optional username (defaults to guest)
     * @returns {Promise} Promise that resolves with search results
     */
    searchRoutes: async function(searchQuery, username = null) {
        try {
            const url = `/api/routes/search/${encodeURIComponent(searchQuery)}`;
            const response = await fetch(url, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json',
                }
            });
            
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            
            return await response.json();
        } catch (error) {
            console.error('Error searching routes:', error);
            throw error;
        }
    }
};
