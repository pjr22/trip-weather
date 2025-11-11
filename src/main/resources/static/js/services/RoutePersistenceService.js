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
            dateTime: waypoint.date && waypoint.time ? this.formatZonedDateTime(waypoint.date, waypoint.time) : null,
            durationMin: waypoint.duration || 0, // Convert null/undefined to 0
            locationName: waypoint.locationName || '',
            latitude: parseFloat(waypoint.lat),
            longitude: parseFloat(waypoint.lng),
            routeId: null // Will be set by the backend
        }));
    },
    
    /**
     * Format date and time to ISO 8601 format with timezone for ZonedDateTime
     * @param {string} date - Date string (yyyy-MM-dd)
     * @param {string} time - Time string (HH:mm)
     * @returns {string} ISO 8601 datetime string with timezone
     */
    formatZonedDateTime: function(date, time) {
        // Create a Date object in the user's local timezone
        const dateTimeString = `${date}T${time}:00`;
        const dateObj = new Date(dateTimeString);
        
        // Return ISO 8601 string with timezone offset
        return dateObj.toISOString();
    },
    
    /**
     * Convert waypoint DTOs back to the UI format
     * @param {Array} waypointDtos - Array of waypoint DTOs from the server
     * @returns {Array} Array of waypoint objects for the UI
     */
    convertWaypointsFromDto: function(waypointDtos) {
        return waypointDtos.map(dto => {
            // Parse ISO 8601 string to get local date and time
            const dateObj = dto.dateTime ? new Date(dto.dateTime) : null;
            
            return {
                uuid: dto.id, // Store the entity UUID
                sequence: dto.sequence, // Store the sequence number
                date: dateObj ? this.formatLocalDate(dateObj) : '', // Extract date part
                time: dateObj ? this.formatLocalTime(dateObj) : '', // Extract time part
                duration: dto.durationMin || 0, // Convert null to 0
                locationName: dto.locationName || '',
                lat: dto.latitude,
                lng: dto.longitude
            };
        });
    },
    
    /**
     * Format Date object to local date string (yyyy-MM-dd)
     * @param {Date} dateObj - Date object
     * @returns {string} Local date string
     */
    formatLocalDate: function(dateObj) {
        const year = dateObj.getFullYear();
        const month = String(dateObj.getMonth() + 1).padStart(2, '0');
        const day = String(dateObj.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
    },
    
    /**
     * Format Date object to local time string (HH:mm)
     * @param {Date} dateObj - Date object
     * @returns {string} Local time string
     */
    formatLocalTime: function(dateObj) {
        const hours = String(dateObj.getHours()).padStart(2, '0');
        const minutes = String(dateObj.getMinutes()).padStart(2, '0');
        return `${hours}:${minutes}`;
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
