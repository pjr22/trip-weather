/**
 * AiProviderService — thin wrapper over /api/ai/providers/* (AI_ASSIST_PLAN.md
 * Phase 3). Stateless like FavoritesService: each call hits the server; no
 * client-side cache of configs.
 *
 * The one bit of cached state is {@link assistEnabled} — a tri-state flag
 * (null = unknown, true, false) the profile menu consults to decide whether to
 * show the "AI Providers" entry. It's populated by {@link refreshAssistEnabled},
 * which probes the auth-gated /available endpoint: 200 means the feature is on,
 * 403 (denyAll when trip.ai.assist.enabled=false) means off.
 *
 * All endpoints require authentication; anonymous calls surface as a thrown
 * Error with .status = 401/403. API keys are never returned by any endpoint.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.AiProvider = {

    /** Tri-state: null = not yet probed, true = enabled, false = disabled. */
    assistEnabled: null,

    /** List the current user's provider configs (no API keys). */
    list: function() {
        return this._getJson('/api/ai/providers');
    },

    /** Fetch one config by id. */
    get: function(id) {
        return this._getJson('/api/ai/providers/' + encodeURIComponent(id));
    },

    /**
     * The provider types this server offers, e.g. {providers:["OPENAI",...]}.
     * Side effect: sets {@link assistEnabled} = true.
     */
    available: function() {
        return this._getJson('/api/ai/providers/available').then(function(body) {
            window.TripWeather.Services.AiProvider.assistEnabled = true;
            return (body && body.providers) || [];
        });
    },

    /**
     * Probe whether the assist feature is enabled, updating {@link assistEnabled}.
     * Never throws — a 403 (feature off) or any error resolves to false.
     * @returns {Promise<boolean>}
     */
    refreshAssistEnabled: function() {
        return this.available()
            .then(function() { return true; })
            .catch(function() {
                window.TripWeather.Services.AiProvider.assistEnabled = false;
                return false;
            });
    },

    /**
     * Create a provider config. Rejects with code DUPLICATE_NICKNAME on 409.
     * @param {object} body - {provider, nickname, model, apiKey?, baseUrl?,
     *                         inputCostPerMtok?, outputCostPerMtok?}
     */
    create: function(body) {
        return this._sendJson('/api/ai/providers', body, 'POST');
    },

    /** Update a config (PUT). Blank apiKey keeps the stored key. */
    update: function(id, body) {
        return this._sendJson('/api/ai/providers/' + encodeURIComponent(id), body, 'PUT');
    },

    /** Soft-delete a config. Resolves on 204. */
    remove: function(id) {
        return this._sendJson('/api/ai/providers/' + encodeURIComponent(id), null, 'DELETE');
    },

    /**
     * Discover models from in-progress create-form credentials.
     * @param {object} args - {provider, apiKey?, baseUrl?}
     * @returns {Promise<string[]>} model ids
     */
    discoverModels: function(args) {
        return this._sendJson('/api/ai/providers/models', args, 'POST')
            .then(function(body) { return (body && body.models) || []; });
    },

    /**
     * Discover models for a saved config using its stored key.
     * @returns {Promise<string[]>} model ids
     */
    discoverModelsForConfig: function(id) {
        return this._getJson('/api/ai/providers/' + encodeURIComponent(id) + '/models')
            .then(function(body) { return (body && body.models) || []; });
    },

    // ---------------- internals ----------------

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

    _sendJson: async function(url, payload, method) {
        const options = {
            method: method,
            headers: {
                'Accept': 'application/json',
                'X-XSRF-TOKEN': window.TripWeather.Utils.Helpers.getCsrfToken()
            },
            credentials: 'same-origin'
        };
        if (payload != null) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(payload);
        }
        const response = await fetch(url, options);
        if (!response.ok) {
            const err = await this._buildError(response);
            if (response.status === 409) {
                err.code = 'DUPLICATE_NICKNAME';
            }
            throw err;
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    },

    _buildError: async function(response) {
        let body = null;
        try { body = await response.json(); } catch (_) { /* ignore */ }
        // Spring's ResponseStatusException puts the reason in "message"; the
        // nested @ResponseStatus exceptions use "error". Prefer whichever is set.
        const message = (body && (body.message || body.error)) || ('HTTP ' + response.status);
        const err = new Error(message);
        err.status = response.status;
        err.body = body;
        return err;
    }
};
