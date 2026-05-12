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
                elevation: 0,
                routable: true,
                original: null,
                snapped: null,
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

        // Reverse-geocode responses carry the snap+elevation info in `data.snapped`.
        // Search-feature inputs (from extractLocationFromFeature) don't — callers
        // separately call resolveLocation for those.
        const original = data.original
            ? { lat: data.original.lat, lng: data.original.lon }
            : null;
        const snapped = data.snapped
            ? {
                lat: data.snapped.lat,
                lng: data.snapped.lon,
                elevation: data.snapped.elevation,
                routable: data.snapped.routable
            }
            : null;
        const elevation = snapped && snapped.elevation != null ? snapped.elevation : 0;
        const routable = snapped ? snapped.routable : true;

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
        }

        return {
            locationName: locationName || 'Unknown',
            elevation: elevation,
            routable: routable,
            original: original,
            snapped: snapped,
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
     * Toast notifications. The implementation lives in
     * /static/js/utils/Toast.js (loaded before this file from index.html)
     * so the admin SPA can use the same code without pulling in all of
     * Helpers.js. These methods are thin wrappers preserved for existing
     * callsites — same signatures, same behaviour.
     */
    configureToasts: function(config) {
        if (window.Toast) window.Toast.configure(config);
    },

    setToastPosition: function(position) {
        if (window.Toast) window.Toast.setPosition(position);
    },

    showToast: function(message, type, duration, options) {
        if (window.Toast) return window.Toast.show(message, type, duration, options);
    },

    /**
     * Backwards compatible wrapper for deprecated alert usage
     * @param {string} message - Message to display
     * @param {string} type - Toast type
     * @param {number} duration - Duration in milliseconds
     */
    showAlert: function(message, type, duration) {
        return this.showToast(message, type, duration);
    },

    /**
     * Format coordinate to fixed decimal places
     * @param {string} coord - Coordinate value
     * @param {number} decimals - Number of decimal places (default 6)
     * @returns {string} - Formatted coordinate
     */
    formatCoordinate: function(coord, decimals) {
        decimals = decimals || 6;
        return parseFloat(coord).toFixed(decimals);
    },

    /**
     * Format altitude as feet from elevation in meters
     * @param {string} elevation - Eleveation in meters
     * @returns {string} - Formatted altitude in feet
     */
    formatElevation: function(elevation) {
        return `${elevation ? Math.floor(parseInt(elevation) * 3.28084) : 0} ft`;
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
     * Escape string for safe HTML insertion
     * @param {*} value - Value to escape
     * @returns {string} - Escaped HTML string
     */
    escapeHtml: function(value) {
        if (value === null || value === undefined) {
            return '';
        }

        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
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
     * Read the Spring Security CSRF token from the XSRF-TOKEN cookie.
     * Spring writes this cookie via CookieCsrfTokenRepository#withHttpOnlyFalse;
     * the SPA must echo it back in the X-XSRF-TOKEN header on every state-changing
     * request or the request is rejected with 403.
     * @returns {string} The token, or '' if the cookie isn't set yet.
     */
    getCsrfToken: function() {
        var name = 'XSRF-TOKEN=';
        var parts = document.cookie ? document.cookie.split(';') : [];
        for (var i = 0; i < parts.length; i++) {
            var c = parts[i].trim();
            if (c.indexOf(name) === 0) {
                return decodeURIComponent(c.substring(name.length));
            }
        }
        return '';
    },

    /**
     * Shared fetch wrapper. Contract:
     *   - 2xx with a JSON body        -> resolves to parsed object/array
     *   - 2xx with an empty body      -> resolves to null (so callers can fall through
     *                                    instead of crashing on JSON.parse of "")
     *   - 4xx / 5xx                   -> rejects with an Error whose .status is the
     *                                    HTTP status code and .body is the parsed
     *                                    response body (JSON if parseable, else text).
     *                                    Error.message includes the server error message
     *                                    when the body is {"error":"..."} or similar.
     *   - network failure             -> rejects with the underlying fetch error
     *
     * @param {string} url
     * @param {RequestInit} [init]
     * @returns {Promise<any>}
     */
    request: function(url, init) {
        return fetch(url, init).then(function(response) {
            return response.text().then(function(bodyText) {
                var body = null;
                if (bodyText) {
                    try {
                        body = JSON.parse(bodyText);
                    } catch (e) {
                        body = bodyText;
                    }
                }

                if (response.ok) {
                    return body;
                }

                var serverMessage = null;
                if (body && typeof body === 'object') {
                    serverMessage = body.error || body.message;
                } else if (typeof body === 'string') {
                    serverMessage = body;
                }

                var errorMessage = 'HTTP ' + response.status;
                if (serverMessage) {
                    errorMessage += ': ' + serverMessage;
                }
                var error = new Error(errorMessage);
                error.status = response.status;
                error.body = body;
                throw error;
            });
        });
    },

    /**
     * Make HTTP GET request. See request() for the resolve / reject contract.
     * @param {string} url - Request URL
     * @returns {Promise<any>}
     */
    httpGet: function(url) {
        return this.request(url);
    },

    /**
     * Make HTTP POST request with a JSON body. See request() for the resolve / reject contract.
     * @param {string} url - Request URL
     * @param {object} data - Data to send as JSON
     * @returns {Promise<any>}
     */
    httpPost: function(url, data) {
        return this.request(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': this.getCsrfToken()
            },
            credentials: 'same-origin',
            body: JSON.stringify(data)
        });
    }
};
