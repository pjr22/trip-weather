/**
 * Wake Lock helper
 * Keeps the screen from sleeping while navigation is active. The lock drops
 * automatically when the page loses visibility, so re-acquire on visibilitychange.
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};

window.TripWeather.Nav.WakeLock = {

    sentinel: null,
    visibilityHandler: null,

    isSupported: function() {
        return 'wakeLock' in navigator;
    },

    request: async function() {
        if (!this.isSupported()) {
            console.warn('Screen Wake Lock API not supported in this browser');
            return false;
        }

        const self = this;
        try {
            self.sentinel = await navigator.wakeLock.request('screen');
            self.sentinel.addEventListener('release', function() {
                self.sentinel = null;
            });
        } catch (err) {
            console.warn('Wake lock request failed:', err.message);
            return false;
        }

        self.visibilityHandler = async function() {
            if (self.sentinel === null && document.visibilityState === 'visible') {
                try {
                    self.sentinel = await navigator.wakeLock.request('screen');
                } catch (err) {
                    // Tab regained focus but lock denied — nothing actionable.
                }
            }
        };
        document.addEventListener('visibilitychange', self.visibilityHandler);
        return true;
    },

    release: function() {
        if (this.sentinel) {
            this.sentinel.release().catch(function() {});
            this.sentinel = null;
        }
        if (this.visibilityHandler) {
            document.removeEventListener('visibilitychange', this.visibilityHandler);
            this.visibilityHandler = null;
        }
    }
};
