/**
 * Duration Utility Functions
 * Handles parsing, formatting, and validation of duration strings
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Utils = window.TripWeather.Utils || {};

window.TripWeather.Utils.Duration = {
    
    /**
     * Parse duration string like "3d2h10m", "2h10m", "1.5h", "45m" into minutes
     * @param {string} durationStr - Duration string to parse
     * @returns {number} - Total minutes
     */
    parseDuration: function(durationStr) {
        if (!durationStr || typeof durationStr !== 'string') {
            return 0;
        }
        
        const trimmed = durationStr.trim().toLowerCase();
        if (!trimmed) {
            return 0;
        }
        
        // Handle pure numbers (treat as minutes)
        if (/^\d+$/.test(trimmed)) {
            return parseInt(trimmed, 10);
        }
        
        let totalMinutes = 0;
        
        // Parse days
        const daysMatch = trimmed.match(/(\d+)\s*d/);
        if (daysMatch) {
            totalMinutes += parseInt(daysMatch[1], 10) * 24 * 60;
        }
        
        // Parse hours (supports decimal hours like "1.5h")
        const hoursMatch = trimmed.match(/(\d+\.?\d*)\s*h/);
        if (hoursMatch) {
            totalMinutes += Math.round(parseFloat(hoursMatch[1]) * 60);
        }
        
        // Parse minutes
        const minutesMatch = trimmed.match(/(\d+)\s*m/);
        if (minutesMatch) {
            totalMinutes += parseInt(minutesMatch[1], 10);
        }
        
        // If no explicit units found, try to parse as minutes
        if (totalMinutes === 0 && /^\d+\.?\d*$/.test(trimmed)) {
            totalMinutes = Math.round(parseFloat(trimmed));
        }
        
        return Math.max(0, totalMinutes);
    },

    /**
     * Format minutes into duration string with consistent format (e.g., "0h00m", "11h20m", "3d12h00m")
     * @param {number} minutes - Total minutes
     * @returns {string} - Formatted duration string
     */
    formatDuration: function(minutes) {
        if (!minutes || minutes <= 0) {
            return '0h00m';
        }
        
        const totalMinutes = Math.round(minutes);
        
        // Calculate days, hours, minutes
        const days = Math.floor(totalMinutes / (24 * 60));
        const remainingMinutes = totalMinutes % (24 * 60);
        const hours = Math.floor(remainingMinutes / 60);
        const mins = remainingMinutes % 60;
        
        // Format hours: 1 digit for 0-9 hours, 2 digits for 10+ hours
        const formattedHours = hours.toString();
        
        // Format minutes with 2 digits (always)
        const formattedMinutes = mins.toString().padStart(2, '0');
        
        let result = '';
        
        if (days > 0) {
            result += `${days}d`;
        }
        
        // Always show hours and minutes
        result += `${formattedHours}h${formattedMinutes}m`;
        
        return result;
    },

    /**
     * Increment duration by specified minutes
     * @param {string} currentDurationStr - Current duration string
     * @param {number} incrementMinutes - Minutes to increment (can be negative)
     * @returns {string} - New duration string
     */
    incrementDuration: function(currentDurationStr, incrementMinutes) {
        const currentMinutes = this.parseDuration(currentDurationStr);
        const newMinutes = Math.max(0, currentMinutes + incrementMinutes);
        return this.formatDuration(newMinutes);
    },

    /**
     * Validate duration input and return corrected version
     * @param {string} input - User input string
     * @returns {object} - { isValid: boolean, correctedValue: string, minutes: number }
     */
    validateDurationInput: function(input) {
        if (!input || typeof input !== 'string') {
            return { isValid: true, correctedValue: '', minutes: 0 };
        }
        
        const trimmed = input.trim();
        if (!trimmed) {
            return { isValid: true, correctedValue: '', minutes: 0 };
        }
        
        const minutes = this.parseDuration(trimmed);
        const formatted = this.formatDuration(minutes);
        
        // Check if the input was already in correct format
        const isValid = trimmed === formatted || /^\d+$/.test(trimmed);
        
        return {
            isValid: isValid,
            correctedValue: formatted,
            minutes: minutes
        };
    }
};
