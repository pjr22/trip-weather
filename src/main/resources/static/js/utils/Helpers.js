/**
 * General Helper Functions
 * Common utility functions used across the application
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Utils = window.TripWeather.Utils || {};

window.TripWeather.Utils.Helpers = {
    
    /**
     * Common function to parse location data from both reverse geocode and search responses
     * @param {object} data - Location data from API response
     * @returns {object} - Parsed location information {locationName, timezone, timezoneName}
     */
    parseLocationData: function(data) {
        if (!data || !data.features || data.features.length === 0) {
            return {
                locationName: 'Unknown',
                timezoneName: '',
                timezoneStdOffset: '',
                timezoneDstOffset: '',
                timezoneStdAbbr: '',
                timezoneDstAbbr: ''
            };
        }
        
        const feature = data.features[0];
        const properties = feature.properties;
        
        // Build location name from address components (same logic as server-side generateLocationName)
        let locationName = '';
        if (properties.address_line1) {
            locationName = properties.address_line1.trim();
        }
        if (properties.city) {
            if (locationName) locationName += ', ';
            locationName += properties.city.trim();
        }
        if (properties.state_code) {
            if (locationName) locationName += ', ';
            locationName += properties.state_code.trim();
        }
        
        // Fallback to address_line2 if we still don't have anything
        if (!locationName && properties.address_line2) {
            locationName = properties.address_line2.trim();
        }
        
        // Final fallback to formatted field
        if (!locationName && properties.formatted) {
            locationName = properties.formatted.trim();
        }
        
        // Extract all timezone information from API response
        let timezoneName = '';
        let timezoneStdOffset = '';
        let timezoneDstOffset = '';
        let timezoneStdAbbr = '';
        let timezoneDstAbbr = '';
        
        if (properties.timezone) {
            timezoneName = properties.timezone.name || '';
            timezoneStdOffset = properties.timezone.offset_STD || '';
            timezoneDstOffset = properties.timezone.offset_DST || '';
            timezoneStdAbbr = properties.timezone.abbreviation_STD || '';
            timezoneDstAbbr = properties.timezone.abbreviation_DST || '';
            
            // Note: The 'timezone' field is deprecated and has been removed
        }
        
        return {
            locationName: locationName || 'Unknown',
            timezoneName: timezoneName,
            timezoneStdOffset: timezoneStdOffset,
            timezoneDstOffset: timezoneDstOffset,
            timezoneStdAbbr: timezoneStdAbbr,
            timezoneDstAbbr: timezoneDstAbbr
        };
    },

    /**
     * Debounce function to limit how often a function can be called
     * @param {Function} func - Function to debounce
     * @param {number} wait - Wait time in milliseconds
     * @returns {Function} - Debounced function
     */
    debounce: function(func, wait) {
        let timeout;
        return function executedFunction() {
            const context = this;
            const args = arguments;
            const later = function() {
                timeout = null;
                func.apply(context, args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    },

    /**
     * Show loading overlay
     * @param {string} overlayId - ID of the overlay element
     */
    showLoading: function(overlayId) {
        const overlay = document.getElementById(overlayId);
        if (overlay) {
            overlay.classList.add('active');
        }
    },

    /**
     * Hide loading overlay
     * @param {string} overlayId - ID of the overlay element
     */
    hideLoading: function(overlayId) {
        const overlay = document.getElementById(overlayId);
        if (overlay) {
            overlay.classList.remove('active');
        }
    },

    /**
     * Show alert message (wrapper for consistency)
     * @param {string} message - Message to display
     */
    showAlert: function(message) {
        alert(message);
    },

    /**
     * Format coordinate to fixed decimal places
     * @param {number} coord - Coordinate value
     * @param {number} decimals - Number of decimal places (default 6)
     * @returns {string} - Formatted coordinate
     */
    formatCoordinate: function(coord, decimals) {
        decimals = decimals || 6;
        return parseFloat(coord).toFixed(decimals);
    },

    /**
     * Generate unique ID for elements
     * @returns {string} - Unique ID
     */
    generateId: function() {
        return 'id_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    },

    /**
     * Check if a value is a valid number
     * @param {*} value - Value to check
     * @returns {boolean} - True if valid number
     */
    isValidNumber: function(value) {
        return !isNaN(parseFloat(value)) && isFinite(value);
    },

    /**
     * Safe JSON parse with error handling
     * @param {string} jsonString - JSON string to parse
     * @param {*} defaultValue - Default value if parse fails
     * @returns {*} - Parsed object or default value
     */
    safeJsonParse: function(jsonString, defaultValue) {
        try {
            return JSON.parse(jsonString);
        } catch (error) {
            console.warn('JSON parse error:', error);
            return defaultValue || null;
        }
    },

    /**
     * Create HTTP query string from parameters
     * @param {object} params - Parameters object
     * @returns {string} - Query string
     */
    createQueryString: function(params) {
        return new URLSearchParams(params).toString();
    },

    /**
     * Make HTTP GET request with error handling
     * @param {string} url - Request URL
     * @returns {Promise} - Promise that resolves to response data
     */
    httpGet: function(url) {
        return fetch(url)
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('HTTP error! status: ' + response.status);
                }
                return response.json();
            });
    },

    /**
     * Make HTTP POST request with error handling
     * @param {string} url - Request URL
     * @param {object} data - Data to send
     * @returns {Promise} - Promise that resolves to response data
     */
    httpPost: function(url, data) {
        return fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(data)
        })
        .then(function(response) {
            if (!response.ok) {
                throw new Error('HTTP error! status: ' + response.status);
            }
            return response.json();
        });
    }
};
