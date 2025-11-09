/**
 * Timezone Utility Functions
 * Handles timezone abbreviation and DST detection
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Utils = window.TripWeather.Utils || {};

window.TripWeather.Utils.Timezone = {
    
    /**
     * Enhanced function to get timezone abbreviation considering DST
     * @param {object} timezoneData - Timezone data from API
     * @param {Date|string} date - Target date for DST calculation
     * @returns {string} - Timezone abbreviation
     */
    getTimezoneAbbrWithDst: function(timezoneData, date) {
        if (!timezoneData) {
            return '';
        }
        
        const targetDate = date ? new Date(date) : new Date();
        
        // If we have timezone name, use the more accurate Intl.DateTimeFormat method
        if (timezoneData.name) {
            return this.getTimezoneAbbr(timezoneData.name, targetDate);
        }
        
        // Fallback to manual STD/DST detection if we have the data
        if (timezoneData.abbreviation_STD && timezoneData.abbreviation_DST) {
            const isDst = this.isDaylightSavingTime(targetDate, timezoneData.name);
            return isDst ? timezoneData.abbreviation_DST : timezoneData.abbreviation_STD;
        }
        
        // Final fallback to STD abbreviation only
        return timezoneData.abbreviation_STD || '';
    },

    /**
     * Get timezone abbreviation for a given timezone and date
     * @param {string} timezoneId - Timezone identifier (e.g., "America/Denver")
     * @param {Date|string} date - Target date
     * @returns {string} - Timezone abbreviation
     */
    getTimezoneAbbr: function(timezoneId, date) {
        // If no timezone provided, return empty
        if (!timezoneId) {
            return '';
        }
        
        try {
            const now = date ? new Date(date) : new Date();
            const formatter = new Intl.DateTimeFormat('en-US', {
                timeZone: timezoneId,
                timeZoneName: 'short'
            });
            
            const parts = formatter.formatToParts(now);
            const timeZoneName = parts.find(part => part.type === 'timeZoneName');
            
            return timeZoneName ? timeZoneName.value : timezoneId.split('/').pop().replace(/_/g, ' ');
        } catch (error) {
            console.warn('Error getting timezone abbreviation:', error);
            return timezoneId.split('/').pop().replace(/_/g, ' ');
        }
    },

    /**
     * Helper function to determine if a date is in DST for a given timezone
     * @param {Date} date - Date to check
     * @param {string} timezoneId - Timezone identifier
     * @returns {boolean} - True if DST is active
     */
    isDaylightSavingTime: function(date, timezoneId) {
        if (!timezoneId) {
            return false;
        }
        
        try {
            // Create dates for January and July to compare timezone offsets
            const jan = new Date(date.getFullYear(), 0, 1);
            const jul = new Date(date.getFullYear(), 6, 1);
            
            const janFormatter = new Intl.DateTimeFormat('en-US', {
                timeZone: timezoneId,
                timeZoneName: 'short'
            });
            
            const julFormatter = new Intl.DateTimeFormat('en-US', {
                timeZone: timezoneId,
                timeZoneName: 'short'
            });
            
            const janParts = janFormatter.formatToParts(jan);
            const julParts = julFormatter.formatToParts(jul);
            
            const janTzName = janParts.find(part => part.type === 'timeZoneName')?.value || '';
            const julTzName = julParts.find(part => part.type === 'timeZoneName')?.value || '';
            
            // Get current date timezone name
            const currentFormatter = new Intl.DateTimeFormat('en-US', {
                timeZone: timezoneId,
                timeZoneName: 'short'
            });
            const currentParts = currentFormatter.formatToParts(date);
            const currentTzName = currentParts.find(part => part.type === 'timeZoneName')?.value || '';
            
            // If current timezone matches July's timezone, it's likely DST
            // This works for most northern hemisphere locations
            return currentTzName === julTzName && janTzName !== julTzName;
            
        } catch (error) {
            console.warn('Error determining DST status:', error);
            return false;
        }
    }
};
