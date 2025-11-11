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
     * Default toast configuration
     */
    toastDefaults: {
        duration: 5000,
        position: 'upper-center'
    },

    _toastContainer: null,
    _toastPositions: [
        'upper-left',
        'upper-center',
        'upper-right',
        'lower-left',
        'lower-center',
        'lower-right'
    ],

    /**
     * Update toast defaults at runtime
     * @param {object} config - Configuration overrides
     */
    configureToasts: function(config) {
        if (!config || typeof config !== 'object') {
            return;
        }

        if (typeof config.duration === 'number') {
            this.toastDefaults.duration = config.duration;
        }

        if (typeof config.position === 'string') {
            this.setToastPosition(config.position);
        }
    },

    /**
     * Set default toast position
     * @param {string} position - Desired toast position
     */
    setToastPosition: function(position) {
        if (!this._isValidToastPosition(position)) {
            console.warn(`Invalid toast position "${position}"`);
            return;
        }

        this.toastDefaults.position = position;
        this._applyToastPosition(position);
    },

    /**
     * Get or create toast container element
     * @returns {HTMLElement} - Toast container element
     */
    getToastContainer: function(positionOverride) {
        if (!this._toastContainer) {
            const container = document.createElement('div');
            container.className = 'toast-container';
            document.body.appendChild(container);
            this._toastContainer = container;
        }
        const targetPosition = this._isValidToastPosition(positionOverride)
            ? positionOverride
            : this.toastDefaults.position;
        this._applyToastPosition(targetPosition);
        return this._toastContainer;
    },

    /**
     * Show toast message
     * @param {string} message - Message to display
     * @param {string} type - Toast type ('success', 'warning', 'error', 'info')
     * @param {number|object} duration - Duration in milliseconds or options object
     * @param {object} [options] - Additional toast options
     */
    showToast: function(message, type, duration, options) {
        const toastType = type || 'info';
        let resolvedOptions = options;
        let durationMs = this.toastDefaults.duration;

        if (typeof duration === 'number') {
            durationMs = duration;
        } else if (duration && typeof duration === 'object') {
            resolvedOptions = duration;
        }

        if (resolvedOptions && typeof resolvedOptions.duration === 'number' && typeof duration !== 'number') {
            durationMs = resolvedOptions.duration;
        }

        const overridePosition = resolvedOptions && resolvedOptions.position;
        const container = this.getToastContainer(overridePosition);

        const toast = document.createElement('div');
        toast.className = `toast toast-${toastType}`;

        const messageSpan = document.createElement('span');
        messageSpan.className = 'toast-message-text';
        messageSpan.textContent = message;

        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'toast-close';
        closeBtn.setAttribute('aria-label', 'Dismiss notification');
        closeBtn.textContent = '×';

        const removeToast = () => {
            toast.classList.remove('show');
            setTimeout(() => {
                if (toast.parentNode === container) {
                    container.removeChild(toast);
                }
            }, 250);
        };

        closeBtn.addEventListener('click', removeToast);

        toast.appendChild(messageSpan);
        toast.appendChild(closeBtn);
        container.appendChild(toast);

        // Trigger animation
        requestAnimationFrame(() => {
            toast.classList.add('show');
        });

        if (durationMs > 0) {
            setTimeout(removeToast, durationMs);
        }

        return toast;
    },

    _isValidToastPosition: function(position) {
        return this._toastPositions.indexOf(position) !== -1;
    },

    _applyToastPosition: function(position) {
        if (!this._toastContainer) {
            return;
        }

        const positionClassPrefix = 'toast-container--';

        this._toastPositions.forEach(pos => {
            this._toastContainer.classList.remove(`${positionClassPrefix}${pos}`);
        });

        const normalizedPosition = this._isValidToastPosition(position)
            ? position
            : this.toastDefaults.position;

        this._toastContainer.classList.add(`${positionClassPrefix}${normalizedPosition}`);
        this._toastContainer.setAttribute('data-toast-position', normalizedPosition);
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
