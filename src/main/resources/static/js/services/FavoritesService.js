/**
 * FavoritesService — thin wrapper over /api/favorites/*.
 *
 * Stateless by design (FAVORITES_AND_ROUTE_MGMT.md decision #9): each call goes
 * back to the server. No in-memory Map, no event bus, no AuthService
 * subscription. Components that need a snapshot hold their own local rendered
 * state for their lifetime and re-fetch on subsequent opens. The cost of the
 * extra same-origin round-trip is negligible (~10-50 ms) and dodges every
 * multi-tab-divergence bug that comes with a client-side cache.
 *
 * All endpoints require authentication; anonymous calls surface as a thrown
 * Error with .status = 403. Callers should only invoke these methods when
 * AuthService.getCurrentUser() is non-null.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.Favorites = {

    /**
     * List the current user's favorites.
     * @param {string} [searchText] - optional substring filter on label OR locationName
     * @returns {Promise<Array>} array of {id, label, locationName, latitude, longitude, elevation, created}
     */
    list: async function(searchText) {
        let url = '/api/favorites';
        if (searchText && searchText.length > 0) {
            url += '?search=' + encodeURIComponent(searchText);
        }
        return this._getJson(url);
    },

    /**
     * Heart-toggle initial-state check for a fresh map-click. Returns the
     * matching favorite if (lat,lon,locationName) equals one of the user's
     * favorites, otherwise null. Phase 3's popup heart uses this when the
     * waypoint object doesn't already carry a favoriteId.
     * @param {object} args - {latitude, longitude, locationName}
     * @returns {Promise<object|null>}
     */
    check: async function(args) {
        const params = new URLSearchParams({
            lat: args.latitude,
            lon: args.longitude,
            locationName: args.locationName || ''
        });
        const response = await fetch('/api/favorites/check?' + params.toString(), {
            method: 'GET',
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
        if (response.status === 204) {
            return null;
        }
        if (!response.ok) {
            throw await this._buildError(response);
        }
        return response.json();
    },

    /**
     * Create a favorite. Rejects with a DuplicateFavoriteLabelError on 409 so
     * callers (popup heart, future inline-add) can surface a useful message
     * without re-checking the status code.
     * @param {object} args - {label, locationName, latitude, longitude, elevation?}
     * @returns {Promise<object>} created favorite DTO
     */
    add: async function(args) {
        try {
            return await this._postJson('/api/favorites', args, 'POST');
        } catch (err) {
            if (err.status === 409) {
                const typed = new Error('A favorite named "' + args.label + '" already exists.');
                typed.status = 409;
                typed.code = 'DUPLICATE_FAVORITE_LABEL';
                throw typed;
            }
            throw err;
        }
    },

    /**
     * Rename a favorite. Only label is mutable per decision in Phase 1.
     * Same 409 mapping as add().
     */
    rename: async function(id, label) {
        try {
            return await this._postJson(
                '/api/favorites/' + encodeURIComponent(id),
                { label: label },
                'PUT');
        } catch (err) {
            if (err.status === 409) {
                const typed = new Error('A favorite named "' + label + '" already exists.');
                typed.status = 409;
                typed.code = 'DUPLICATE_FAVORITE_LABEL';
                throw typed;
            }
            throw err;
        }
    },

    /** Soft-delete a favorite. Resolves on 204. */
    remove: async function(id) {
        const response = await fetch('/api/favorites/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: { 'X-XSRF-TOKEN': window.TripWeather.Utils.Helpers.getCsrfToken() },
            credentials: 'same-origin'
        });
        if (!response.ok) {
            throw await this._buildError(response);
        }
    },

    _getJson: async function(url) {
        const response = await fetch(url, {
            method: 'GET',
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        });
        if (!response.ok) {
            throw await this._buildError(response);
        }
        return response.json();
    },

    _postJson: async function(url, payload, method) {
        const response = await fetch(url, {
            method: method || 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': window.TripWeather.Utils.Helpers.getCsrfToken()
            },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            throw await this._buildError(response);
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    },

    _buildError: async function(response) {
        let body = null;
        try { body = await response.json(); } catch (_) { /* ignore */ }
        const message = (body && (body.error || body.message)) || ('HTTP ' + response.status);
        const err = new Error(message);
        err.status = response.status;
        err.body = body;
        return err;
    }
};
