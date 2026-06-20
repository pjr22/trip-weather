/**
 * AiResolutionModal — the AI-assist resolution modal (AI_ASSIST_PLAN.md
 * Phase 4d). Opened by AiAssistModal whenever the assist response carries
 * warnings (at least one unresolved stop, or a route-level warning). It lists
 * EVERY stop in sequence order — resolved and unresolved alike — so the user
 * can edit, re-search, or drop any of them before committing.
 *
 * Each row tracks: { sequence, text, status, lat, lon, matchedAddress,
 * resolvedText }. A row is "dirty" (needs re-geocoding) when it's unresolved or
 * when its text differs from resolvedText. Re-search re-geocodes only the dirty
 * rows via the SPA's existing forward-geocode (/api/location/search, first
 * feature — the same first-match rule the backend applies). "Use this route"
 * applies the resolved (✓) rows in sequence order via
 * AiAssistModal.applyWaypoints; still-unresolved rows are simply left out.
 *
 * Nothing touches the map until "Use this route" — the modal is a clean gate.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.AiResolutionModal = {

    initialized: false,
    rows: [],            // row state for the modal's lifetime
    warnings: [],        // route-level warnings (read-only display)
    response: null,      // the assist response that opened this modal (for the details panel)
    searching: false,

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;
        this.setupModalCloseAffordances();
        this.setupControls();
        this.subscribeAuthChanges();
    },

    subscribeAuthChanges: function() {
        const auth = window.TripWeather.Services.Auth;
        if (auth && typeof auth.onChange === 'function') {
            auth.onChange(function(user) {
                if (!user) this.close();
            }.bind(this));
        }
    },

    setupModalCloseAffordances: function() {
        const modal = document.getElementById('ai-resolution-modal');
        if (!modal) return;

        const closeBtn = modal.querySelector('.modal-header .close');
        if (closeBtn) closeBtn.addEventListener('click', this.close.bind(this));

        modal.addEventListener('click', function(event) {
            if (event.target === modal) this.close();
        }.bind(this));
        document.addEventListener('keydown', function(event) {
            // Don't close when the details panel is layered on top — Escape
            // there should dismiss only the details modal.
            const details = document.getElementById('ai-details-modal');
            const detailsOpen = details && details.style.display === 'block';
            if (event.key === 'Escape' && modal.style.display === 'block' && !detailsOpen) {
                this.close();
            }
        }.bind(this));
    },

    setupControls: function() {
        const researchBtn = document.getElementById('ai-resolution-research-btn');
        if (researchBtn) researchBtn.addEventListener('click', this.research.bind(this));

        const detailsBtn = document.getElementById('ai-resolution-details-btn');
        if (detailsBtn) {
            detailsBtn.addEventListener('click', function() {
                const details = window.TripWeather.Managers.AiDetailsModal;
                if (details && this.response) details.open(this.response);
            }.bind(this));
        }

        const useBtn = document.getElementById('ai-resolution-use-btn');
        if (useBtn) useBtn.addEventListener('click', this.useRoute.bind(this));

        const cancelBtn = document.getElementById('ai-resolution-cancel-btn');
        if (cancelBtn) cancelBtn.addEventListener('click', this.close.bind(this));
    },

    // ---------------- lifecycle ----------------

    /**
     * Open the modal with an assist response. Builds one row per stop (resolved
     * waypoints + unresolved locations), sorted into the AI's sequence order.
     */
    open: function(response) {
        const modal = document.getElementById('ai-resolution-modal');
        if (!modal) return;

        this.response = response;
        const resolved = (response && response.waypoints) || [];
        const unresolved = (response && response.unresolved) || [];
        this.warnings = (response && response.warnings) || [];

        const rows = [];
        resolved.forEach(function(w) {
            rows.push({
                sequence: w.sequence,
                text: w.locationName || '',
                status: 'resolved',
                lat: w.latitude,
                lon: w.longitude,
                matchedAddress: w.locationName || '',
                resolvedText: w.locationName || ''
            });
        });
        unresolved.forEach(function(u) {
            rows.push({
                sequence: u.sequence,
                text: u.query || '',
                status: 'unresolved',
                lat: null,
                lon: null,
                matchedAddress: null,
                resolvedText: null
            });
        });
        rows.sort(function(a, b) { return a.sequence - b.sequence; });
        this.rows = rows;

        this.searching = false;
        this.setStatus('');
        modal.style.display = 'block';
        this.renderWarnings();
        this.renderRows();
        this.updateUseButton();
    },

    close: function() {
        const modal = document.getElementById('ai-resolution-modal');
        if (modal) modal.style.display = 'none';
        this.rows = [];
        this.warnings = [];
        this.response = null;
        this.searching = false;
    },

    setStatus: function(text) {
        const el = document.getElementById('ai-resolution-status');
        if (el) el.textContent = text || '';
    },

    // ---------------- rendering ----------------

    renderWarnings: function() {
        const box = document.getElementById('ai-resolution-warnings');
        if (!box) return;
        box.innerHTML = '';
        if (!this.warnings || this.warnings.length === 0) {
            box.style.display = 'none';
            return;
        }
        box.style.display = '';
        const heading = document.createElement('div');
        heading.className = 'ai-resolution-warnings-title';
        heading.textContent = 'Notes';
        box.appendChild(heading);
        const ul = document.createElement('ul');
        this.warnings.forEach(function(w) {
            const li = document.createElement('li');
            li.textContent = w;
            ul.appendChild(li);
        });
        box.appendChild(ul);
    },

    renderRows: function() {
        const list = document.getElementById('ai-resolution-rows');
        if (!list) return;
        list.innerHTML = '';

        this.rows.forEach(function(row, index) {
            list.appendChild(this.buildRow(row, index));
        }.bind(this));
    },

    buildRow: function(row, index) {
        const dirty = this.isDirty(row);

        const wrap = document.createElement('div');
        wrap.className = 'ai-resolution-row' + (dirty ? ' dirty' : '');

        // Status icon — green ✓ resolved, red ✗ unresolved.
        const icon = document.createElement('span');
        icon.className = 'ai-resolution-status ' + (row.status === 'resolved'
            ? 'ai-resolution-status-ok' : 'ai-resolution-status-bad');
        icon.textContent = row.status === 'resolved' ? '✓' : '✗';
        icon.setAttribute('aria-label', row.status === 'resolved' ? 'Found' : 'Not found');

        // Running 1-based position in the (sequence-sorted) list. The internal
        // row.sequence drives ordering + dirty tracking; this is just the
        // human-facing travel-order number.
        const num = document.createElement('span');
        num.className = 'ai-resolution-num';
        num.textContent = String(index + 1);

        // Editable text field.
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'ai-resolution-input';
        input.value = row.text;
        input.placeholder = 'City, state or address';
        input.addEventListener('input', function() {
            row.text = input.value;
            wrap.classList.toggle('dirty', this.isDirty(row));
        }.bind(this));

        // Delete this stop.
        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'ai-resolution-delete';
        del.textContent = 'Delete';
        del.addEventListener('click', function() {
            this.rows.splice(this.rows.indexOf(row), 1);
            this.renderRows();
            this.updateUseButton();
        }.bind(this));

        wrap.appendChild(icon);
        wrap.appendChild(num);
        wrap.appendChild(input);
        wrap.appendChild(del);
        return wrap;
    },

    /** A row needs re-geocoding when unresolved or edited since it last resolved. */
    isDirty: function(row) {
        if (row.status !== 'resolved') return true;
        return row.text !== row.resolvedText;
    },

    /** Count of currently-resolved (✓) rows. */
    resolvedCount: function() {
        return this.rows.filter(function(r) { return r.status === 'resolved'; }).length;
    },

    updateUseButton: function() {
        const useBtn = document.getElementById('ai-resolution-use-btn');
        const hint = document.getElementById('ai-resolution-use-hint');
        const ok = this.resolvedCount() >= 2;
        if (useBtn) useBtn.disabled = !ok || this.searching;
        if (hint) hint.style.display = ok ? 'none' : '';
    },

    setSearching: function(busy) {
        this.searching = busy;
        const researchBtn = document.getElementById('ai-resolution-research-btn');
        const cancelBtn = document.getElementById('ai-resolution-cancel-btn');
        if (researchBtn) {
            researchBtn.disabled = busy;
            researchBtn.textContent = busy ? 'Searching…' : 'Re-search';
        }
        if (cancelBtn) cancelBtn.disabled = busy;
        this.updateUseButton();
    },

    // ---------------- re-search ----------------

    /**
     * Re-geocode every dirty row (unresolved or edited). Untouched resolved
     * rows keep their coordinates — no wasted lookups. Each row's icon updates
     * from the result.
     */
    research: function() {
        if (this.searching) return;

        const dirtyRows = this.rows.filter(function(r) { return this.isDirty(r); }.bind(this));
        if (dirtyRows.length === 0) {
            this.setStatus('Nothing to re-search — every stop already resolves.');
            return;
        }

        this.setStatus('Re-searching ' + dirtyRows.length
            + ' stop' + (dirtyRows.length === 1 ? '' : 's') + '…');
        this.setSearching(true);

        const tasks = dirtyRows.map(function(row) {
            return this.geocodeRow(row);
        }.bind(this));

        Promise.all(tasks).then(function() {
            this.setSearching(false);
            this.renderRows();
            this.updateUseButton();
            const stillBad = this.rows.filter(function(r) { return r.status !== 'resolved'; }).length;
            this.setStatus(stillBad === 0
                ? 'All stops resolved.'
                : (stillBad + ' stop' + (stillBad === 1 ? '' : 's') + ' still not found — edit or delete.'));
        }.bind(this));
    },

    /**
     * Geocode one row's current text, taking the first feature (the same
     * first-match rule the backend uses). Resolves the promise either way —
     * the row's status reflects the outcome.
     */
    geocodeRow: function(row) {
        const query = (row.text || '').trim();
        if (!query) {
            row.status = 'unresolved';
            row.lat = row.lon = null;
            row.matchedAddress = null;
            row.resolvedText = null;
            return Promise.resolve();
        }
        return window.TripWeather.Services.Location.searchLocations(query)
            .then(function(data) {
                const feature = data && data.features && data.features[0];
                const point = feature ? this.extractPoint(feature) : null;
                if (point) {
                    row.status = 'resolved';
                    row.lat = point.lat;
                    row.lon = point.lon;
                    row.matchedAddress = point.address || query;
                    // Show the confirmed match and mark the row clean.
                    row.text = row.matchedAddress;
                    row.resolvedText = row.matchedAddress;
                } else {
                    row.status = 'unresolved';
                    row.lat = row.lon = null;
                    row.matchedAddress = null;
                    row.resolvedText = null;
                }
            }.bind(this))
            .catch(function() {
                row.status = 'unresolved';
                row.lat = row.lon = null;
                row.matchedAddress = null;
                row.resolvedText = null;
            });
    },

    /** Pull lat/lon + formatted address from a Geoapify feature (mirrors backend). */
    extractPoint: function(feature) {
        const props = feature.properties || {};
        let lat = typeof props.lat === 'number' ? props.lat : null;
        let lon = typeof props.lon === 'number' ? props.lon : null;
        if (lat == null || lon == null) {
            const coords = feature.geometry && feature.geometry.coordinates;
            if (coords && coords.length >= 2) {
                lon = coords[0];
                lat = coords[1];
            }
        }
        if (lat == null || lon == null) return null;
        return { lat: lat, lon: lon, address: props.formatted || null };
    },

    // ---------------- apply ----------------

    /**
     * Build the final waypoint list from the resolved rows in sequence order
     * and hand it to AiAssistModal.applyWaypoints (the shared load + calculate
     * path). Leftover unresolved rows are dropped.
     */
    useRoute: function() {
        if (this.searching) return;
        const waypoints = this.rows
            .filter(function(r) { return r.status === 'resolved' && r.lat != null && r.lon != null; })
            .sort(function(a, b) { return a.sequence - b.sequence; })
            .map(function(r) {
                return { lat: r.lat, lon: r.lon, locationName: r.matchedAddress || r.text };
            });

        if (waypoints.length < 2) {
            this.setStatus('Need at least 2 resolved stops to build a route.');
            return;
        }

        const assist = window.TripWeather.Managers.AiAssistModal;
        this.close();
        if (assist && typeof assist.applyWaypoints === 'function') {
            assist.applyWaypoints(waypoints);
        } else {
            window.Toast.show('Could not load the route.', 'error');
        }
    }
};
