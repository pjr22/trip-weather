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
     * Resolve a lat/lng to a navigation-ready point with elevation. The
     * server snaps the input to the road network, retrieves elevation from
     * the routing graph (or falls back to public terrain elevation when the
     * input is off-road), and returns both the original input and the
     * snapped point along with a `routable` flag.
     *
     * Server returns `{ original: {lat,lon}, snapped: {lat,lon,elevation,routable} }`.
     * This wrapper renames `lon` → `lng` so the rest of the JS, which uses
     * the Leaflet-style `lng`, doesn't have to special-case this endpoint.
     *
     * @param {number} latitude - Input latitude
     * @param {number} longitude - Input longitude
     * @returns {Promise<object>} - Resolved location with original + snapped points
     */
    resolveLocation: function(latitude, longitude) {
        const params = {
            lat: latitude,
            lon: longitude
        };

        const url = '/api/route/elevation?' + window.TripWeather.Utils.Helpers.createQueryString(params);

        return window.TripWeather.Utils.Helpers.httpGet(url)
            .then(function(resolution) {
                if (!resolution) return null;
                return {
                    original: resolution.original
                        ? { lat: resolution.original.lat, lng: resolution.original.lon }
                        : null,
                    snapped: resolution.snapped
                        ? {
                            lat: resolution.snapped.lat,
                            lng: resolution.snapped.lon,
                            elevation: resolution.snapped.elevation,
                            routable: resolution.snapped.routable
                        }
                        : null
                };
            })
            .catch(function(error) {
                console.error('Location resolve error:', error);
                throw error;
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
