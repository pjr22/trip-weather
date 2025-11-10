/**
 * Timezone Utility Functions
 * Handles timezone abbreviation and DST detection
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Utils = window.TripWeather.Utils || {};

window.TripWeather.Utils.Timezone = {
    
    /**
     * Get timezone abbreviation from stored waypoint data
     * @param {object} waypoint - Waypoint object with timezone information
     * @param {Date|string} date - Target date for DST calculation
     * @returns {string} - Timezone abbreviation
     */
    getTimezoneAbbrFromWaypoint: function(waypoint, date) {
        if (!waypoint || !waypoint.timezoneName) {
            return '';
        }
        
        const targetDate = date ? new Date(date) : new Date();
        
        // Determine if DST is active for this date
        const isDst = this.isDaylightSavingTimeForDate(targetDate, waypoint);
        
        // Return appropriate abbreviation
        return isDst ? waypoint.timezoneDstAbbr : waypoint.timezoneStdAbbr;
    },
    
    /**
     * Determine if a date is during daylight saving time for a waypoint
     * @param {Date} date - Date to check
     * @param {object} waypoint - Waypoint with timezone information
     * @returns {boolean} - True if DST is active
     */
    isDaylightSavingTimeForDate: function(date, waypoint) {
        // If we don't have timezone information, assume standard time
        if (!waypoint.timezoneStdOffset || !waypoint.timezoneDstOffset) {
            return false;
        }
        
        // More accurate DST detection using a heuristic approach
        // This is still simplified but more accurate than just checking months
        
        // Get the year from the date
        const year = date.getFullYear();
        const month = date.getMonth(); // 0-11 (Jan=0)
        const day = date.getDate();
        
        // For US timezones, DST typically runs from the second Sunday in March
        // to the first Sunday in November
        if (this.isUSTimezone(waypoint.timezoneName)) {
            // Calculate DST start date (second Sunday in March)
            const marchDSTStart = this.calculateNthDayOfMonth(year, 2, 0, 2); // March, Sunday(0), 2nd
            
            // Calculate DST end date (first Sunday in November)
            const novDSTEnd = this.calculateNthDayOfMonth(year, 10, 0, 1); // November, Sunday(0), 1st
            
            // DST is active between these dates (inclusive)
            return date >= marchDSTStart && date < novDSTEnd;
        }
        
        // For European timezones, DST typically runs from the last Sunday in March
        // to the last Sunday in October
        if (this.isEuropeanTimezone(waypoint.timezoneName)) {
            // Calculate DST start date (last Sunday in March)
            const marchDSTStart = this.calculateLastDayOfMonth(year, 2, 0); // March, Sunday(0)
            
            // Calculate DST end date (last Sunday in October)
            const octDSTEnd = this.calculateLastDayOfMonth(year, 9, 0); // October, Sunday(0)
            
            // DST is active between these dates (inclusive)
            return date >= marchDSTStart && date < octDSTEnd;
        }
        
        // For southern hemisphere timezones (like Australia), DST runs from
        // October to April (roughly)
        if (this.isSouthernHemisphereTimezone(waypoint.timezoneName)) {
            // DST runs from first Sunday in October to first Sunday in April
            const octDSTStart = this.calculateNthDayOfMonth(year, 9, 0, 1); // October, Sunday(0), 1st
            const aprDSTEnd = this.calculateNthDayOfMonth(year, 3, 0, 1); // April, Sunday(0), 1st
            
            // DST is active between these dates (inclusive)
            return date >= octDSTStart || date < aprDSTEnd;
        }
        
        // Default to no DST for unknown timezones
        return false;
    },
    
    /**
     * Check if timezone is in the US
     * @param {string} timezoneName - Timezone name
     * @returns {boolean} - True if US timezone
     */
    isUSTimezone: function(timezoneName) {
        return timezoneName && (
            timezoneName.startsWith('America/') &&
            !timezoneName.includes('Argentina') &&
            !timezoneName.includes('Brazil') &&
            !timezoneName.includes('Canada') &&
            !timezoneName.includes('Chile') &&
            !timezoneName.includes('Mexico') &&
            !timezoneName.includes('Peru') &&
            !timezoneName.includes('Santo_Domingo')
        );
    },
    
    /**
     * Check if timezone is in Europe
     * @param {string} timezoneName - Timezone name
     * @returns {boolean} - True if European timezone
     */
    isEuropeanTimezone: function(timezoneName) {
        return timezoneName && (
            timezoneName.startsWith('Europe/') ||
            timezoneName.includes('London') ||
            timezoneName.includes('Berlin') ||
            timezoneName.includes('Paris') ||
            timezoneName.includes('Rome')
        );
    },
    
    /**
     * Check if timezone is in the southern hemisphere
     * @param {string} timezoneName - Timezone name
     * @returns {boolean} - True if southern hemisphere timezone
     */
    isSouthernHemisphereTimezone: function(timezoneName) {
        return timezoneName && (
            timezoneName.includes('Australia/') ||
            timezoneName.includes('Pacific/Auckland') ||
            timezoneName.includes('Africa/') ||
            timezoneName.includes('America/Santiago') ||
            timezoneName.includes('America/Sao_Paulo')
        );
    },
    
    /**
     * Calculate the nth day of the week in a month
     * @param {number} year - Year
     * @param {number} month - Month (0-11)
     * @param {number} dayOfWeek - Day of week (0-6, 0=Sunday)
     * @param {number} n - Nth occurrence (1, 2, 3, 4, 5)
     * @returns {Date} - Date object
     */
    calculateNthDayOfMonth: function(year, month, dayOfWeek, n) {
        const firstDay = new Date(year, month, 1);
        const firstDayOfWeek = firstDay.getDay();
        
        // Calculate days to add to get to the first occurrence of the desired day of week
        let daysToAdd = (dayOfWeek - firstDayOfWeek + 7) % 7;
        
        // Add weeks to get to the nth occurrence
        daysToAdd += (n - 1) * 7;
        
        return new Date(year, month, 1 + daysToAdd);
    },
    
    /**
     * Calculate the last day of the week in a month
     * @param {number} year - Year
     * @param {number} month - Month (0-11)
     * @param {number} dayOfWeek - Day of week (0-6, 0=Sunday)
     * @returns {Date} - Date object
     */
    calculateLastDayOfMonth: function(year, month, dayOfWeek) {
        const lastDay = new Date(year, month + 1, 0); // Last day of month
        const lastDayOfWeek = lastDay.getDay();
        
        // Calculate days to subtract to get to the last occurrence of the desired day of week
        let daysToSubtract = (lastDayOfWeek - dayOfWeek + 7) % 7;
        
        return new Date(year, month, lastDay.getDate() - daysToSubtract);
    },
    
    /**
     * Get timezone offset from stored waypoint data
     * @param {object} waypoint - Waypoint object with timezone information
     * @param {Date|string} date - Target date for DST calculation
     * @returns {string} - Timezone offset (e.g., "-07:00")
     */
    getTimezoneOffsetFromWaypoint: function(waypoint, date) {
        if (!waypoint || !waypoint.timezoneName) {
            return '';
        }
        
        const targetDate = date ? new Date(date) : new Date();
        
        // Determine if DST is active for this date
        const isDst = window.TripWeather.Managers.Waypoint.isDaylightSavingTimeForDate(targetDate, waypoint);
        
        // Return appropriate offset
        return isDst ? waypoint.timezoneDstOffset : waypoint.timezoneStdOffset;
    },
    
    /**
     * Legacy function for backward compatibility
     * @deprecated Use getTimezoneAbbrFromWaypoint instead
     */
    getTimezoneAbbr: function(timezoneId, date) {
        console.warn('getTimezoneAbbr is deprecated. Use getTimezoneAbbrFromWaypoint instead.');
        return timezoneId || '';
    }
};
