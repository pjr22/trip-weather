/**
 * Weather Service
 * Handles weather data fetching and processing
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.Weather = {
    
    /**
     * Fetch weather forecast for a specific location and time
     * @param {number} latitude - Latitude coordinate
     * @param {number} longitude - Longitude coordinate
     * @param {string} date - Date in YYYY-MM-DD format
     * @param {string} time - Time in HH:MM format
     * @returns {Promise<object>} - Promise that resolves to weather data
     */
    getWeatherForecast: function(latitude, longitude, date, time) {
        const params = {
            latitude: latitude,
            longitude: longitude
        };
        
        if (date) params.date = date;
        if (time) params.time = time;
        
        const url = '/api/weather/forecast?' + window.TripWeather.Utils.Helpers.createQueryString(params);
        
        return window.TripWeather.Utils.Helpers.httpGet(url)
            .catch(function(error) {
                console.warn('Failed to fetch weather data:', error);
                return { error: 'Failed to fetch weather data' };
            });
    },

    /**
     * Get weather for a waypoint with caching
     * @param {object} waypoint - Waypoint object with lat, lng, date, time
     * @returns {Promise<object>} - Promise that resolves to weather data
     */
    getWeatherForWaypoint: function(waypoint) {
        if (!waypoint || !waypoint.lat || !waypoint.lng) {
            return Promise.resolve({ error: 'Invalid waypoint coordinates' });
        }
        
        // Create cache key based on location and time
        const cacheKey = this._createWeatherCacheKey(waypoint);
        
        // Check cache first
        if (this._weatherCache && this._weatherCache[cacheKey]) {
            return Promise.resolve(this._weatherCache[cacheKey]);
        }
        
        return this.getWeatherForecast(waypoint.lat, waypoint.lng, waypoint.date, waypoint.time)
            .then(function(weatherData) {
                // Cache the result
                if (!window.TripWeather.Services.Weather._weatherCache) {
                    window.TripWeather.Services.Weather._weatherCache = {};
                }
                window.TripWeather.Services.Weather._weatherCache[cacheKey] = weatherData;
                return weatherData;
            });
    },

    /**
     * Format weather data for display in UI
     * @param {object} weather - Weather data object
     * @returns {object} - Formatted weather information
     */
    formatWeatherDisplay: function(weather) {
        if (!weather || weather.error) {
            return {
                condition: weather?.error || 'No data',
                temperature: '-',
                wind: '-',
                precipitation: '-',
                iconUrl: null
            };
        }
        
        const tempDisplay = weather.temperature ? 
            `${weather.temperature}°${weather.temperatureUnit}` : 'N/A';
        const windDisplay = `${weather.windSpeed} ${weather.windDirection}`;
        const precipDisplay = weather.precipitationProbability !== null ? 
            `${weather.precipitationProbability}%` : '-';
        
        return {
            condition: weather.condition || 'Unknown',
            temperature: tempDisplay,
            wind: windDisplay,
            precipitation: precipDisplay,
            iconUrl: weather.iconUrl || null
        };
    },

    /**
     * Generate HTML for weather display in table cells
     * @param {object} weather - Weather data object
     * @param {boolean} isLoading - Whether weather is currently loading
     * @returns {string} - HTML string for weather cells
     */
    generateWeatherHtml: function(weather, isLoading) {
        const helpers = window.TripWeather.Utils.Helpers;

        if (isLoading) {
            return `
                <td colspan="4" class="weather-loading">Loading weather...</td>
            `;
        }
        
        if (weather && weather.error) {
            const safeError = helpers.escapeHtml(weather.error);
            return `
                <td colspan="4" class="weather-error">${safeError}</td>
            `;
        }
        
        if (weather) {
            const formatted = this.formatWeatherDisplay(weather);
            const safeCondition = helpers.escapeHtml(formatted.condition);
            const safeTemperature = helpers.escapeHtml(formatted.temperature);
            const safeWind = helpers.escapeHtml(formatted.wind);
            const safePrecipitation = helpers.escapeHtml(formatted.precipitation);
            
            let weatherIcon = '';
            if (formatted.iconUrl) {
                const safeIconUrl = helpers.escapeHtml(formatted.iconUrl);
                weatherIcon = `<img src="${safeIconUrl}" alt="${safeCondition}" class="weather-icon"> `;
            }
            
            return `
                <td>${weatherIcon}${safeCondition}</td>
                <td>${safeTemperature}</td>
                <td>${safeWind}</td>
                <td>${safePrecipitation}</td>
            `;
        }
        
        return `
            <td>-</td>
            <td>-</td>
            <td>-</td>
            <td>-</td>
        `;
    },

    /**
     * Generate HTML for weather display in popup
     * @param {object} weather - Weather data object
     * @returns {string} - HTML string for weather popup content
     */
    generateWeatherPopupHtml: function(weather) {
        const helpers = window.TripWeather.Utils.Helpers;

        if (!weather || weather.error) {
            return helpers.escapeHtml(weather?.error || 'No weather data available');
        }
        
        let html = '';
        
        if (weather.iconUrl) {
            const safeIconUrl = helpers.escapeHtml(weather.iconUrl);
            const safeConditionText = helpers.escapeHtml(weather.condition);
            html += `<img src="${safeIconUrl}" alt="${safeConditionText}" class="weather-icon"> `;
        }
        
        html += `${helpers.escapeHtml(weather.condition)}<br>`;
        
        if (weather.temperature) {
            const tempDisplay = `${weather.temperature}°${weather.temperatureUnit}`;
            html += `Temp: ${helpers.escapeHtml(tempDisplay)}<br>`;
        }
        
        const windText = helpers.escapeHtml(`Wind: ${weather.windSpeed} ${weather.windDirection}`);
        html += `${windText}<br>`;
        
        if (weather.precipitationProbability !== null) {
            const precipText = helpers.escapeHtml(`Precip: ${weather.precipitationProbability}%`);
            html += precipText;
        }
        
        return html;
    },

    /**
     * Clear the weather cache
     */
    clearCache: function() {
        this._weatherCache = {};
    },

    /**
     * Create a cache key for weather data
     * @param {object} waypoint - Waypoint object
     * @returns {string} - Cache key
     * @private
     */
    _createWeatherCacheKey: function(waypoint) {
        const key = `${waypoint.lat},${waypoint.lng}`;
        const parts = [];
        
        if (waypoint.date) {
            parts.push(waypoint.date);
        }
        if (waypoint.time) {
            parts.push(waypoint.time);
        }
        
        return parts.length > 0 ? key + '_' + parts.join('_') : key;
    },

    /**
     * Private cache for weather data
     * @private
     */
    _weatherCache: {}
};
