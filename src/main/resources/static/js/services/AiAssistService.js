/**
 * AiAssistService — thin wrapper over POST /api/ai/assist (AI_ASSIST_PLAN.md
 * Phase 4). Stateless like AiProviderService: the one call hits the server and
 * returns the parsed assist response.
 *
 * The response shape (Phase 4a):
 *   {
 *     waypoints:  [{ sequence, latitude, longitude, locationName, city, state, elevation }],
 *     route:      RouteData | null,
 *     unresolved: [{ sequence, query }],
 *     warnings:   [string]    // route-level only
 *   }
 *
 * Requires authentication and the assist feature enabled; anonymous / disabled
 * calls surface as a thrown Error with .status (401 / 403). The provider-config
 * list comes from {@link AiProviderService}; this service only runs the assist.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.AiAssist = {

    /**
     * Run an AI assist request.
     * @param {object} args - { providerConfigId, prompt }
     * @returns {Promise<object>} the assist response
     */
    submit: function(args) {
        return this._sendJson('/api/ai/assist', {
            providerConfigId: args.providerConfigId,
            prompt: args.prompt
        }, 'POST');
    },

    // ---------------- internals (mirror AiProviderService) ----------------

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
        const message = (body && (body.message || body.error)) || ('HTTP ' + response.status);
        const err = new Error(message);
        err.status = response.status;
        err.body = body;
        return err;
    }
};
