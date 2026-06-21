/**
 * AiAssistModal — the "AI Assist" submit dialog (AI_ASSIST_PLAN.md Phase 4b/4c).
 * Opened from the #ai-assist-btn toolbar button (wired in app.js). Lets the user
 * pick one of their saved AI provider configs and type a free-text trip
 * description, then POSTs to /api/ai/assist.
 *
 * Result routing (4c):
 *  - clean response (no unresolved stops, no route-level warnings, route
 *    present) → load the resolved waypoints straight into the working route;
 *  - any unresolved stop or route-level warning → hand the response to the
 *    resolution modal so the user can edit / re-search / drop stops first.
 *
 * The actual waypoint load (clear → add in sequence order → calculate route)
 * lives in {@link applyWaypoints} so the resolution modal's "Use this route"
 * shares exactly one code path with the clean direct-load.
 *
 * Mirrors AiProvidersModal's structure and conventions.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.AiAssistModal = {

    initialized: false,
    configs: [],       // provider-config summaries for the current dialog session
    submitting: false,
    lastResult: null,  // most recent assist response (for the on-demand details panel)
    buttonMode: 'assist', // 'assist' (submit dialog) | 'results' (details panel)

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;

        this.setupModalCloseAffordances();
        this.setupFormControls();
        this.subscribeAuthChanges();
    },

    subscribeAuthChanges: function() {
        // Close the dialog if the user logs out while it's open. Button
        // visibility is handled in app.js (auth + assistEnabled gating).
        const auth = window.TripWeather.Services.Auth;
        if (auth && typeof auth.onChange === 'function') {
            auth.onChange(function(user) {
                if (!user) this.close();
            }.bind(this));
        }
    },

    // ---------------- modal lifecycle ----------------

    setupModalCloseAffordances: function() {
        const modal = document.getElementById('ai-assist-modal');
        if (!modal) return;

        const closeBtn = modal.querySelector('.modal-header .close');
        if (closeBtn) closeBtn.addEventListener('click', this.close.bind(this));

        modal.addEventListener('click', function(event) {
            if (event.target === modal) this.close();
        }.bind(this));
        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') this.close();
        }.bind(this));
    },

    setupFormControls: function() {
        const submitBtn = document.getElementById('ai-assist-submit-btn');
        if (submitBtn) submitBtn.addEventListener('click', this.submit.bind(this));

        const cancelBtn = document.getElementById('ai-assist-cancel-btn');
        if (cancelBtn) cancelBtn.addEventListener('click', this.close.bind(this));

        const cta = document.getElementById('ai-assist-manage-providers');
        if (cta) {
            cta.addEventListener('click', function(event) {
                event.preventDefault();
                this.close();
                const mgr = window.TripWeather.Managers.AiProvidersModal;
                if (mgr && typeof mgr.open === 'function') mgr.open();
            }.bind(this));
        }
    },

    open: function() {
        const auth = window.TripWeather.Services.Auth;
        if (!auth || !auth.getCurrentUser()) return;

        const modal = document.getElementById('ai-assist-modal');
        if (!modal) return;
        modal.style.display = 'block';

        this.submitting = false;
        this.setStatus('Loading providers…');
        this.setSubmitting(false);

        const promptField = document.getElementById('ai-assist-prompt-field');
        if (promptField) promptField.value = '';

        window.TripWeather.Services.AiProvider.list()
            .then(function(configs) {
                this.configs = configs || [];
                this.populateProviders();
                this.setStatus('');
            }.bind(this))
            .catch(function(err) {
                this.configs = [];
                this.populateProviders();
                this.setStatus(err.status === 403
                    ? 'The AI assistant is not enabled on this server.'
                    : ('Could not load providers: ' + err.message));
            }.bind(this));
    },

    close: function() {
        const modal = document.getElementById('ai-assist-modal');
        if (modal) modal.style.display = 'none';
        this.submitting = false;
    },

    setStatus: function(text) {
        const el = document.getElementById('ai-assist-modal-status');
        if (el) el.textContent = text || '';
    },

    /** Populate the provider <select>; show the "no providers" CTA when empty. */
    populateProviders: function() {
        const sel = document.getElementById('ai-assist-provider-field');
        const cta = document.getElementById('ai-assist-no-providers');
        const formRow = document.getElementById('ai-assist-provider-row');
        if (!sel) return;

        sel.innerHTML = '';
        const hasConfigs = this.configs.length > 0;

        this.configs.forEach(function(cfg) {
            const opt = document.createElement('option');
            opt.value = cfg.id;
            // Nickname is the primary handle; model in parens for disambiguation.
            opt.textContent = cfg.nickname + (cfg.model ? ' (' + cfg.model + ')' : '');
            sel.appendChild(opt);
        });

        if (cta) cta.style.display = hasConfigs ? 'none' : '';
        if (formRow) formRow.style.display = hasConfigs ? '' : 'none';

        const submitBtn = document.getElementById('ai-assist-submit-btn');
        if (submitBtn) submitBtn.disabled = !hasConfigs;
    },

    /** Toggle the in-flight state: spinner + label + disabled controls. */
    setSubmitting: function(busy) {
        this.submitting = busy;
        const submitBtn = document.getElementById('ai-assist-submit-btn');
        const cancelBtn = document.getElementById('ai-assist-cancel-btn');
        const sel = document.getElementById('ai-assist-provider-field');
        const promptField = document.getElementById('ai-assist-prompt-field');
        const spinner = document.getElementById('ai-assist-spinner');
        if (submitBtn) {
            submitBtn.disabled = busy || this.configs.length === 0;
            submitBtn.textContent = busy ? 'Working…' : 'Submit';
        }
        // Cancel + inputs lock during the call. (Cancel stays disabled because
        // the fetch isn't abortable yet — letting it dismiss mid-flight would
        // race the in-flight response onto the map. Abortable cancel is a
        // possible follow-up.)
        if (cancelBtn) cancelBtn.disabled = busy;
        if (sel) sel.disabled = busy;
        if (promptField) promptField.disabled = busy;
        if (spinner) spinner.style.display = busy ? 'inline-block' : 'none';
    },

    // ---------------- submit ----------------

    submit: function() {
        if (this.submitting) return;

        const sel = document.getElementById('ai-assist-provider-field');
        const promptField = document.getElementById('ai-assist-prompt-field');
        const providerConfigId = sel ? sel.value : '';
        const prompt = promptField ? promptField.value.trim() : '';

        if (!providerConfigId) {
            this.setStatus('Add an AI provider first, then pick one here.');
            return;
        }
        if (!prompt) {
            this.setStatus('Describe the trip you want help planning.');
            return;
        }

        this.setStatus('Asking the assistant — this can take up to a minute or two for reasoning models…');
        this.setSubmitting(true);

        // Total AI Assist response time = Submit click → this dialog closes (which
        // handleResponse does the moment the response arrives, before any routing
        // or weather). So this round-trip is exactly the window the user waits on.
        const startedAt = this.nowMs();

        window.TripWeather.Services.AiAssist.submit({ providerConfigId: providerConfigId, prompt: prompt })
            .then(function(response) {
                this.setSubmitting(false);
                if (response) {
                    // Carry the user's freeform route description along with the
                    // response (the server doesn't echo it back), plus the measured
                    // round-trip so the AI Results panel can break down the wait.
                    response.promptText = prompt;
                    response.clientTotalMs = Math.round(this.nowMs() - startedAt);
                }
                this.handleResponse(response);
            }.bind(this))
            .catch(function(err) {
                this.setSubmitting(false);
                this.setStatus('Assist failed: ' + (err.message || err));
            }.bind(this));
    },

    /**
     * Route the assist response: clean → direct load; any unresolved stop or
     * route-level warning → resolution modal. No toast for warnings.
     */
    handleResponse: function(response) {
        this.lastResult = response;
        const unresolved = (response && response.unresolved) || [];
        const warnings = (response && response.warnings) || [];
        const route = response && response.route;
        const hasWarnings = unresolved.length > 0 || warnings.length > 0;

        if (!hasWarnings && route) {
            // Clean: load the resolved waypoints straight into the working route.
            const waypoints = ((response && response.waypoints) || [])
                .slice()
                .sort(function(a, b) { return a.sequence - b.sequence; })
                .map(function(w) {
                    return { lat: w.latitude, lon: w.longitude, locationName: w.locationName };
                });
            this.close();
            this.applyWaypoints(waypoints);
            return;
        }

        // Warnings (unresolved stops or route-level notes): let the user adjust.
        this.close();
        const resolution = window.TripWeather.Managers.AiResolutionModal;
        if (resolution && typeof resolution.open === 'function') {
            resolution.open(response);
        } else {
            window.Toast.show('Could not open the resolution dialog.', 'error');
        }
    },

    /**
     * Load a final, ordered waypoint list into the working route and calculate
     * it once. Shared by the clean direct-load and the resolution modal's "Use
     * this route". Each item: { lat, lon, locationName }.
     *
     * Mirrors SearchManager.selectRouteSearchResult: clear → add → calculate.
     * Waypoints carry only their name; calculateRoute fills in arrival times,
     * timezones, distances, and weather from the server response.
     */
    applyWaypoints: function(waypoints) {
        if (!waypoints || waypoints.length < 2) {
            window.Toast.show('Need at least 2 locations to build a route.', 'warning');
            return;
        }

        const app = window.TripWeather.App;
        const waypointMgr = window.TripWeather.Managers.Waypoint;
        const routeMgr = window.TripWeather.Managers.Route;
        if (!waypointMgr || !routeMgr) {
            window.Toast.show('Route managers not available.', 'error');
            return;
        }

        // Clear the working route to an unsaved state, preserving the owning
        // user so a later Save Route ties to their account.
        if (app && typeof app.resetCurrentRoute === 'function') {
            app.resetCurrentRoute({ preserveUserId: true });
        } else {
            waypointMgr.clearAllWaypoints();
        }

        waypoints.forEach(function(w) {
            waypointMgr.addWaypoint(w.lat, w.lon, 0, { locationName: w.locationName || '' });
        });

        routeMgr.calculateRoute();
        window.Toast.show('Loaded ' + waypoints.length + ' stops from the assistant.', 'success');
        this.enterResultsMode();
    },

    // ---------------- toolbar button mode ----------------
    //
    // The single #ai-assist-btn toggles between two states:
    //   - 'assist'  (blue "AI Assist")  → opens the submit dialog
    //   - 'results' (green "AI Results") → opens the details panel for the last run
    // It flips to 'results' once a route is loaded from an assist run, and back
    // to 'assist' when the working route is cleared (New Route / Load Route — see
    // app.js resetCurrentRoute + SearchManager.selectRouteSearchResult).

    /** Monotonic-ish millisecond clock for timing the request round-trip. */
    nowMs: function() {
        return (window.performance && typeof performance.now === 'function')
            ? performance.now() : Date.now();
    },

    handleButtonClick: function() {
        if (this.buttonMode === 'results' && this.lastResult) {
            const details = window.TripWeather.Managers.AiDetailsModal;
            if (details && typeof details.open === 'function') {
                details.open(this.lastResult);
                return;
            }
        }
        this.open();
    },

    /** Flip the toolbar button to the green "AI Results" state. No-op without a
     *  result to show. */
    enterResultsMode: function() {
        if (!this.lastResult) return;
        this.buttonMode = 'results';
        this.refreshButton();
    },

    /** Restore the toolbar button to the blue "AI Assist" state. Called when the
     *  working route is cleared. */
    enterAssistMode: function() {
        this.buttonMode = 'assist';
        this.refreshButton();
    },

    refreshButton: function() {
        const btn = document.getElementById('ai-assist-btn');
        if (!btn) return;
        const results = this.buttonMode === 'results';
        const extra = btn.querySelector('.btn-label-extra');
        if (extra) extra.textContent = results ? 'Results' : 'Assist';
        btn.classList.toggle('ai-results-mode', results);
        btn.title = results ? 'View the last AI Assist results' : 'Plan a route with AI';
    }
};
