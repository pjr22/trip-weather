/**
 * AiDetailsModal — read-only "AI Assist details" panel (AI_ASSIST_PLAN.md
 * Phase 4 detail surfacing). Opened on demand after an assist run from:
 *   - the success toast's "View AI details" action (clean direct-load), and
 *   - the resolution modal's "Details" button.
 *
 * Shows what the model returned and what it cost: model id, token usage
 * (prompt / completion / total — "—" when the provider didn't report them),
 * elapsed time, every suggested stop in sequence order (✓ resolved / ✗ not
 * found), and the raw model response. All values come from the assist
 * response's always-on `details` object plus its `waypoints` / `unresolved`.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.AiDetailsModal = {

    initialized: false,

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;
        this.setupModalCloseAffordances();
    },

    setupModalCloseAffordances: function() {
        const modal = document.getElementById('ai-details-modal');
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

    /**
     * @param {object} response - the full /api/ai/assist response
     */
    open: function(response) {
        const modal = document.getElementById('ai-details-modal');
        if (!modal || !response) return;

        this.renderDescription(response.promptText || '');
        this.renderSummary(response.details || {});
        this.renderStops(response.waypoints || [], response.unresolved || []);
        this.renderRaw((response.details && response.details.rawResponse) || '');

        modal.style.display = 'block';
    },

    close: function() {
        const modal = document.getElementById('ai-details-modal');
        if (modal) modal.style.display = 'none';
    },

    // ---------------- rendering ----------------

    /** The freeform trip text the user submitted. Hidden if somehow absent. */
    renderDescription: function(text) {
        const section = document.getElementById('ai-details-description-section');
        const body = document.getElementById('ai-details-description');
        if (!section || !body) return;
        if (!text) {
            section.style.display = 'none';
            return;
        }
        section.style.display = '';
        body.textContent = text; // user-supplied text
    },

    renderSummary: function(details) {
        const el = document.getElementById('ai-details-summary');
        if (!el) return;
        el.innerHTML = '';

        const tok = function(n) { return (n == null) ? '—' : String(n); };
        const seconds = details.elapsedMs != null
            ? (details.elapsedMs / 1000).toFixed(1) + ' s'
            : '—';

        const rows = [
            ['Model', details.model || '—'],
            ['Tokens (prompt / completion / total)',
                tok(details.promptTokens) + ' / ' + tok(details.completionTokens)
                + ' / ' + tok(details.totalTokens)],
            ['Response time', seconds]
        ];

        rows.forEach(function(pair) {
            const row = document.createElement('div');
            row.className = 'ai-details-summary-row';
            const k = document.createElement('span');
            k.className = 'ai-details-summary-key';
            k.textContent = pair[0];
            const v = document.createElement('span');
            v.className = 'ai-details-summary-val';
            v.textContent = pair[1];
            row.appendChild(k);
            row.appendChild(v);
            el.appendChild(row);
        });
    },

    renderStops: function(waypoints, unresolved) {
        const el = document.getElementById('ai-details-stops');
        if (!el) return;
        el.innerHTML = '';

        const rows = [];
        waypoints.forEach(function(w) {
            rows.push({ sequence: w.sequence, resolved: true, text: w.locationName || '' });
        });
        unresolved.forEach(function(u) {
            rows.push({ sequence: u.sequence, resolved: false, text: u.query || '' });
        });
        rows.sort(function(a, b) { return a.sequence - b.sequence; });

        if (rows.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'ai-details-empty';
            empty.textContent = 'The assistant returned no stops.';
            el.appendChild(empty);
            return;
        }

        rows.forEach(function(r, index) {
            const row = document.createElement('div');
            row.className = 'ai-details-stop';

            const icon = document.createElement('span');
            icon.className = 'ai-resolution-status ' + (r.resolved
                ? 'ai-resolution-status-ok' : 'ai-resolution-status-bad');
            icon.textContent = r.resolved ? '✓' : '✗';

            const num = document.createElement('span');
            num.className = 'ai-resolution-num';
            num.textContent = String(index + 1);

            const text = document.createElement('span');
            text.className = 'ai-details-stop-text';
            text.textContent = r.text;

            row.appendChild(icon);
            row.appendChild(num);
            row.appendChild(text);
            el.appendChild(row);
        });
    },

    /**
     * Render the model's response. The model is asked for a JSON object of
     * locations, so when the raw text parses as that, present it as a readable
     * numbered list (never raw JSON to the user). Anything that isn't the
     * expected location JSON — an error, a refusal, prose — falls back to the
     * actual text in a <pre>, since that's the diagnosable part.
     */
    renderRaw: function(raw) {
        const el = document.getElementById('ai-details-raw');
        if (!el) return;
        el.innerHTML = '';

        const text = (raw && raw.length) ? raw : '';
        if (!text) {
            el.appendChild(this.rawPre('(empty)'));
            return;
        }

        const locations = this.extractLocations(text);
        if (locations && locations.length) {
            const list = document.createElement('div');
            list.className = 'ai-details-stops';
            locations.forEach(function(loc, i) {
                const row = document.createElement('div');
                row.className = 'ai-details-stop';

                const num = document.createElement('span');
                num.className = 'ai-resolution-num';
                num.textContent = String(i + 1);

                const t = document.createElement('span');
                t.className = 'ai-details-stop-text';
                t.textContent = this.formatLocation(loc);

                row.appendChild(num);
                row.appendChild(t);
                list.appendChild(row);
            }.bind(this));
            el.appendChild(list);
        } else {
            el.appendChild(this.rawPre(text));
        }
    },

    /**
     * Pull a locations array out of the model's JSON, tolerating the documented
     * shape ({"locations":[...]}) or a bare array. Returns null when the text
     * isn't JSON or carries no recognizable location objects.
     */
    extractLocations: function(text) {
        let parsed;
        try {
            parsed = JSON.parse(text);
        } catch (e) {
            return null;
        }
        let arr = null;
        if (parsed && Array.isArray(parsed.locations)) {
            arr = parsed.locations;
        } else if (Array.isArray(parsed)) {
            arr = parsed;
        }
        if (!arr) return null;
        const objects = arr.filter(function(x) { return x && typeof x === 'object'; });
        return objects.length ? objects : null;
    },

    /** "Moab, UT" from {name:'Moab', city:'Moab', state:'UT'} — dedupes repeats. */
    formatLocation: function(loc) {
        const parts = [];
        [loc.name, loc.city, loc.state].forEach(function(p) {
            const s = (p == null) ? '' : String(p).trim();
            if (s && parts.indexOf(s) === -1) parts.push(s);
        });
        return parts.length ? parts.join(', ') : JSON.stringify(loc);
    },

    rawPre: function(text) {
        const pre = document.createElement('pre');
        pre.className = 'ai-details-raw-pre';
        pre.textContent = text; // untrusted model output
        return pre;
    }
};
