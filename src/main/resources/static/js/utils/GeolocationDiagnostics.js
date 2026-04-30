/**
 * Geolocation Diagnostics
 * Helpers for detecting and explaining the most common reasons that
 * navigator.geolocation calls fail without ever prompting the user.
 *
 * Three failure modes drive almost all "the location prompt never appeared"
 * reports we get from real users:
 *   - Page is loaded over plain HTTP — Safari (and most modern browsers)
 *     silently disable geolocation outside a secure context.
 *   - Permission was denied on a previous visit and the browser cached the
 *     decision; subsequent watchPosition calls reject without re-prompting.
 *   - OS-level Location Services / Safari Websites toggle is off.
 *
 * precheck() catches the first synchronously. queryPermissionState() detects
 * a stale denial proactively. describeError() turns the GeolocationPositionError
 * code into a user-actionable sentence so the toast tells people what to fix.
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Utils = window.TripWeather.Utils || {};

window.TripWeather.Utils.GeolocationDiagnostics = {

    // GeolocationPositionError code constants (W3C Geolocation API).
    PERMISSION_DENIED: 1,
    POSITION_UNAVAILABLE: 2,
    TIMEOUT: 3,

    isSupported: function() {
        return 'geolocation' in navigator;
    },

    // window.isSecureContext is true on HTTPS and on localhost — exactly the
    // contexts where geolocation is allowed to prompt.
    isSecureContext: function() {
        return window.isSecureContext === true;
    },

    /**
     * Synchronous pre-flight check. Returns { ok: true } when geolocation can
     * at least be attempted, or { ok: false, code, message } describing the
     * blocking issue. The shape mimics GeolocationPositionError closely enough
     * that callers can hand it to options.onError without special-casing.
     */
    precheck: function() {
        if (!this.isSupported()) {
            return {
                ok: false,
                code: 0,
                message: 'Geolocation is not supported by this browser.'
            };
        }
        if (!this.isSecureContext()) {
            return {
                ok: false,
                code: 0,
                message: 'Location requires a secure (HTTPS) connection. '
                    + 'Reload this page using https:// to enable location.'
            };
        }
        return { ok: true };
    },

    /**
     * Map a GeolocationPositionError (or precheck-shaped object) to a
     * user-actionable message. PERMISSION_DENIED with no secure context is
     * almost always the HTTPS issue, so prefer that diagnosis when both apply.
     */
    describeError: function(error) {
        if (!error) return 'Unknown location error.';

        if (!this.isSecureContext()) {
            return 'Location is blocked because this page is not loaded over HTTPS. '
                + 'Reload using https:// to enable location.';
        }

        if (error.code === this.PERMISSION_DENIED) {
            return 'Location permission was denied. In Safari, tap "aA" in the address '
                + 'bar → Website Settings → Location → Ask, then reload. '
                + 'Also check iOS Settings → Privacy & Security → Location Services → '
                + 'Safari Websites is set to "While Using the App".';
        }
        if (error.code === this.POSITION_UNAVAILABLE) {
            return 'Your device could not determine its location. '
                + 'Check that Location Services / GPS is enabled and try again.';
        }
        if (error.code === this.TIMEOUT) {
            return 'Location request timed out. Move to an area with a clearer view '
                + 'of the sky and try again.';
        }
        return error.message
            ? 'Unable to get your location: ' + error.message
            : 'Unable to get your current location.';
    },

    /**
     * Async Permissions API probe. Resolves to one of:
     *   'granted' | 'denied' | 'prompt' | null
     * null means the Permissions API is unavailable or the query failed —
     * callers should treat null as "unknown, fall through to the live request".
     */
    queryPermissionState: function() {
        if (!navigator.permissions || typeof navigator.permissions.query !== 'function') {
            return Promise.resolve(null);
        }
        try {
            return navigator.permissions.query({ name: 'geolocation' })
                .then(function(status) { return status && status.state ? status.state : null; })
                .catch(function() { return null; });
        } catch (e) {
            return Promise.resolve(null);
        }
    }
};
