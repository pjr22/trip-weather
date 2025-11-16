/**
 * Location Service
 * Handles geocoding, reverse geocoding, and location data operations
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.Location = {
    
    /**
     * Perform reverse geocoding to get location name and timezone from coordinates
     * @param {number} latitude - Latitude coordinate
     * @param {number} longitude - Longitude coordinate
     * @returns {Promise<object>} - Promise that resolves to location information
     */
    reverseGeocode: function(latitude, longitude) {
        const params = {
            latitude: latitude,
            longitude: longitude
        };
        
        const url = '/api/location/reverse?' + window.TripWeather.Utils.Helpers.createQueryString(params);
        
        return window.TripWeather.Utils.Helpers.httpGet(url)
            .then(function(data) {
                return window.TripWeather.Utils.Helpers.parseLocationData(data);
            })
            .catch(function(error) {
                console.warn('Failed to fetch location name:', error);
                return {
                    locationName: 'Unknown',
                    elevation: 0,
                    timezone: '',
                    timezoneName: ''
                };
            });
    },

    /**
     * Search for locations by query string
     * @param {string} query - Search query (city, address, place name)
     * @returns {Promise<object>} - Promise that resolves to search results
     */
    searchLocations: function(query) {
        const params = {
            query: query
        };
        
        const url = '/api/location/search?' + window.TripWeather.Utils.Helpers.createQueryString(params);
        
        return window.TripWeather.Utils.Helpers.httpGet(url)
            .catch(function(error) {
                console.error('Search error:', error);
                throw new Error('Error performing search');
            });
    },

    /**
     * Fetch elevation for a given location
     * @param {number} latitude - Latitude coordinate
     * @param {number} longitude - Longitude coordinate
     * @returns {Promise<number>} - Promise that resolves to elevation in meters
     */
    getElevation: function(latitude, longitude) {
        const params = {
            lat: latitude,
            lon: longitude
        };
        
        const url = '/api/route/elevation?' + window.TripWeather.Utils.Helpers.createQueryString(params);
        
        return window.TripWeather.Utils.Helpers.httpGet(url)
            .then(function(elevation) {
                return elevation;
            })
            .catch(function(error) {
                console.error('Elevation fetch error:', error);
                throw error;
            });
    },

    /**
     * Validate if a location is routeable using the snap endpoint
     * @param {number} latitude - Latitude coordinate
     * @param {number} longitude - Longitude coordinate
     * @returns {Promise<boolean>} - Promise that resolves to true if location is routeable, false otherwise
     */
    snapToLocation: function(latitude, longitude) {
        const params = {
            lat: latitude,
            lon: longitude
        };
        
        const url = '/api/route/snap?' + window.TripWeather.Utils.Helpers.createQueryString(params);
        
        return window.TripWeather.Utils.Helpers.httpGet(url)
            .then(function(response) {
                // Check if the response has features array and it's not empty
                if (response && response.features && Array.isArray(response.features)) {
                    return response.features.length > 0;
                }
                return false;
            })
            .catch(function(error) {
                console.error('Snap validation error:', error);
                // If there's an error with the snap endpoint, assume the location is not routeable
                return false;
            });
    },

    /**
     * Get location name, elevation and timezone for coordinates (with caching)
     * @param {number} latitude - Latitude coordinate
     * @param {number} longitude - Longitude coordinate
     * @returns {Promise<object>} - Promise that resolves to location information
     */
    getLocationInfo: function(latitude, longitude) {
        // Simple caching - could be enhanced with more sophisticated caching
        const cacheKey = latitude + ',' + longitude;
        
        if (this._locationCache && this._locationCache[cacheKey]) {
            return Promise.resolve(this._locationCache[cacheKey]);
        }
        
        return this.reverseGeocode(latitude, longitude)
            .then(function(locationInfo) {
                // Cache the result
                if (!window.TripWeather.Services.Location._locationCache) {
                    window.TripWeather.Services.Location._locationCache = {};
                }
                window.TripWeather.Services.Location._locationCache[cacheKey] = locationInfo;
                return locationInfo;
            });
    },

    /**
     * Extract location information from a search result feature
     * @param {object} feature - GeoJSON feature from search results
     * @returns {object} - Extracted location information
     */
    extractLocationFromFeature: function(feature) {
        if (!feature || !feature.properties) {
            return {
                locationName: 'Unknown',
                timezone: '',
                timezoneName: '',
                timezoneStdOffset: '',
                timezoneDstOffset: '',
                timezoneStdAbbr: '',
                timezoneDstAbbr: '',
                coordinates: null
            };
        }
        
        const properties = feature.properties;
        const coordinates = feature.geometry ? feature.geometry.coordinates : null;
        
        // Create a temporary data object to use the common parseLocationData function
        const tempData = { features: [feature] };
        const locationInfo = window.TripWeather.Utils.Helpers.parseLocationData(tempData);
        
        // Add coordinates if available
        if (coordinates && coordinates.length >= 2) {
            locationInfo.coordinates = {
                lat: coordinates[1],
                lng: coordinates[0]
            };
        }
        
        return locationInfo;
    },

    /**
     * Format location display text from search result
     * @param {object} feature - GeoJSON feature
     * @returns {object} - Formatted display information {label, details}
     */
    formatLocationDisplay: function(feature) {
        if (!feature || !feature.properties) {
            return { label: 'Unknown', details: '' };
        }
        
        const properties = feature.properties;
        const locationInfo = this.extractLocationFromFeature(feature);
        const label = locationInfo.locationName;
        
        const details = [];
        if (properties.city) details.push(properties.city);
        if (properties.state) details.push(properties.state);
        if (properties.country) details.push(properties.country);
        
        return {
            label: label,
            details: details.join(', ')
        };
    },

    /**
     * Clear the location cache
     */
    clearCache: function() {
        this._locationCache = {};
    },

    /**
     * Private cache for location data
     * @private
     */
    _locationCache: {}
};
