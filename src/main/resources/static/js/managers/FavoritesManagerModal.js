/**
 * FavoritesManagerModal — owns the "My Favorites" manager modal and its two
 * entry points (the profile-menu item, dispatched by UIManager, and the
 * top-left Leaflet overlay button installed here).
 *
 * Phase 2 scope (FAVORITES_AND_ROUTE_MGMT.md):
 *  - Open from either entry point → fetch /api/favorites → render rows
 *  - Per-row actions: Add to current route, Rename (inline), Delete (confirm)
 *  - No client-side cache; every open re-fetches (decision #9)
 *  - No "create new" affordance — that lands in Phase 3 (popup heart toggle)
 *  - Anonymous users see neither entry point
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.FavoritesManagerModal = {

    initialized: false,
    overlayContainer: null,
    overlayControl: null,
    rows: [],                  // local snapshot for the modal's lifetime
    editingRowId: null,        // id of the row whose label is in inline-edit mode

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;

        this.setupModalCloseAffordances();
        this.setupMapOverlay();
        this.subscribeAuthChanges();
        this.applyAuthVisibility();
    },

    /**
     * Auth gating. Anonymous → hide the overlay button and (defensively) close
     * the modal if it's open at the moment of logout.
     */
    subscribeAuthChanges: function() {
        const auth = window.TripWeather.Services.Auth;
        if (auth && typeof auth.onChange === 'function') {
            auth.onChange(function(user) {
                this.applyAuthVisibility();
                if (!user) {
                    this.close();
                }
            }.bind(this));
        }
    },

    applyAuthVisibility: function() {
        if (!this.overlayContainer) return;
        const auth = window.TripWeather.Services.Auth;
        const user = auth ? auth.getCurrentUser() : null;
        this.overlayContainer.style.display = user ? '' : 'none';
    },

    // ---------------- Leaflet overlay button ----------------

    setupMapOverlay: function() {
        const checkMapInterval = setInterval(function() {
            const map = window.TripWeather.Managers.Map
                && window.TripWeather.Managers.Map.getMap();
            if (map) {
                clearInterval(checkMapInterval);
                this.addOverlayControl(map);
            }
        }.bind(this), 500);
    },

    addOverlayControl: function(map) {
        const self = this;

        const FavoritesOverlayControl = L.Control.extend({
            options: { position: 'topleft' },
            onAdd: function() {
                const container = L.DomUtil.create('div', 'leaflet-bar favorites-control');
                container.title = 'My favorites';
                container.id = 'favorites-overlay-btn';
                window.TripWeather.Utils.IconLoader.loadSvgIcon(
                    'icons/heart-filled.svg', container, 'favorites-icon');
                if (container.innerHTML === '') {
                    container.innerHTML = '<span>♥</span>';
                }
                L.DomEvent.on(container, 'click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    L.DomEvent.preventDefault(e);
                    self.open();
                });
                self.overlayContainer = container;
                return container;
            }
        });

        this.overlayControl = new FavoritesOverlayControl();
        map.addControl(this.overlayControl);
        this.applyAuthVisibility();
    },

    // ---------------- Modal lifecycle ----------------

    setupModalCloseAffordances: function() {
        const modal = document.getElementById('favorites-modal');
        if (!modal) return;

        const closeBtn = modal.querySelector('.modal-header .close');
        if (closeBtn) {
            closeBtn.addEventListener('click', this.close.bind(this));
        }
        modal.addEventListener('click', function(event) {
            if (event.target === modal) this.close();
        }.bind(this));
        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                this.close();
            }
        }.bind(this));

        const searchInput = document.getElementById('favorites-search-input');
        if (searchInput) {
            // Client-side filter over the in-memory snapshot — no server
            // round-trip per keystroke. Re-fetch happens on next open.
            searchInput.addEventListener('input', this.renderRows.bind(this));
        }
    },

    open: function() {
        const auth = window.TripWeather.Services.Auth;
        if (!auth || !auth.getCurrentUser()) {
            // Defensive: shouldn't be reachable since both entry points are
            // auth-gated, but a stale overlay click during logout could land here.
            return;
        }

        const modal = document.getElementById('favorites-modal');
        if (!modal) return;
        modal.style.display = 'block';

        // Reset search box on each open; the snapshot is fresh anyway.
        const searchInput = document.getElementById('favorites-search-input');
        if (searchInput) searchInput.value = '';

        this.editingRowId = null;
        this.setStatus('Loading…');

        window.TripWeather.Services.Favorites.list()
            .then(function(favorites) {
                this.rows = favorites || [];
                this.setStatus('');
                this.renderRows();
            }.bind(this))
            .catch(function(err) {
                this.rows = [];
                this.setStatus('Could not load favorites: ' + err.message);
                this.renderRows();
            }.bind(this));
    },

    close: function() {
        const modal = document.getElementById('favorites-modal');
        if (modal) modal.style.display = 'none';
        this.editingRowId = null;
    },

    setStatus: function(text) {
        const el = document.getElementById('favorites-modal-status');
        if (el) el.textContent = text || '';
    },

    // ---------------- Row rendering ----------------

    renderRows: function() {
        const tbody = document.getElementById('favorites-modal-tbody');
        const emptyEl = document.getElementById('favorites-modal-empty');
        if (!tbody) return;

        tbody.innerHTML = '';

        const searchInput = document.getElementById('favorites-search-input');
        const q = (searchInput && searchInput.value || '').trim().toLowerCase();
        const filtered = q.length === 0
            ? this.rows
            : this.rows.filter(function(f) {
                return (f.label || '').toLowerCase().indexOf(q) !== -1
                    || (f.locationName || '').toLowerCase().indexOf(q) !== -1;
            });

        if (filtered.length === 0) {
            if (emptyEl) {
                emptyEl.style.display = '';
                if (this.rows.length === 0) {
                    emptyEl.textContent = 'No favorites yet — "heart" a waypoint from the map to add one.';
                } else {
                    emptyEl.textContent = 'No favorites match your search.';
                }
            }
            return;
        }
        if (emptyEl) emptyEl.style.display = 'none';

        filtered.forEach(function(fav) {
            tbody.appendChild(this.buildRow(fav));
        }.bind(this));
    },

    buildRow: function(fav) {
        const tr = document.createElement('tr');
        tr.dataset.favoriteId = fav.id;

        // Label cell — either text or an inline editor
        const labelTd = document.createElement('td');
        labelTd.className = 'favorites-cell-label';
        if (this.editingRowId === fav.id) {
            const input = document.createElement('input');
            input.type = 'text';
            input.className = 'favorites-rename-input';
            input.value = fav.label;
            input.maxLength = 255;
            input.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    this.commitRename(fav, input.value);
                } else if (e.key === 'Escape') {
                    e.preventDefault();
                    this.editingRowId = null;
                    this.renderRows();
                }
            }.bind(this));
            labelTd.appendChild(input);
            // Focus after appending so selectionStart works
            setTimeout(function() { try { input.focus(); input.select(); } catch (_) {} }, 0);
        } else {
            labelTd.textContent = fav.label;
        }

        const locTd = document.createElement('td');
        locTd.className = 'favorites-cell-location';
        locTd.textContent = fav.locationName || '';

        const coordTd = document.createElement('td');
        coordTd.className = 'favorites-cell-coord';
        coordTd.textContent = this.formatCoord(fav.latitude, fav.longitude);

        const actionsTd = document.createElement('td');
        actionsTd.className = 'favorites-cell-actions';
        if (this.editingRowId === fav.id) {
            const saveBtn = document.createElement('button');
            saveBtn.className = 'modal-btn primary small-btn';
            saveBtn.textContent = 'Save';
            saveBtn.addEventListener('click', function() {
                const input = labelTd.querySelector('input');
                this.commitRename(fav, input ? input.value : fav.label);
            }.bind(this));
            const cancelBtn = document.createElement('button');
            cancelBtn.className = 'modal-btn secondary small-btn';
            cancelBtn.textContent = 'Cancel';
            cancelBtn.addEventListener('click', function() {
                this.editingRowId = null;
                this.renderRows();
            }.bind(this));
            actionsTd.appendChild(saveBtn);
            actionsTd.appendChild(cancelBtn);
        } else {
            actionsTd.appendChild(this.makeActionButton('Add', 'add-to-route',
                this.handleAddToRoute.bind(this, fav)));
            actionsTd.appendChild(this.makeActionButton('Rename', 'rename',
                function() {
                    this.editingRowId = fav.id;
                    this.renderRows();
                }.bind(this)));
            actionsTd.appendChild(this.makeActionButton('Delete', 'delete-favorite',
                this.handleDelete.bind(this, fav)));
        }

        tr.appendChild(labelTd);
        tr.appendChild(locTd);
        tr.appendChild(coordTd);
        tr.appendChild(actionsTd);
        return tr;
    },

    makeActionButton: function(label, kind, handler) {
        const btn = document.createElement('button');
        btn.className = 'favorites-row-action favorites-row-action-' + kind;
        btn.textContent = label;
        btn.addEventListener('click', handler);
        return btn;
    },

    formatCoord: function(lat, lon) {
        if (lat == null || lon == null) return '';
        return Number(lat).toFixed(5) + ', ' + Number(lon).toFixed(5);
    },

    // ---------------- Row action handlers ----------------

    handleAddToRoute: function(fav) {
        const waypointMgr = window.TripWeather.Managers.Waypoint;
        if (!waypointMgr || typeof waypointMgr.addWaypoint !== 'function') {
            window.Toast.show('Waypoint manager not available', 'error');
            return;
        }

        // Reuse the same shape SearchManager builds — a locationInfo object
        // with at least locationName lets the new waypoint render with the
        // correct address immediately (no reverse-geocode round-trip).
        const locationInfo = {
            locationName: fav.locationName || ''
        };
        waypointMgr.addWaypoint(fav.latitude, fav.longitude, fav.elevation, locationInfo);
        window.Toast.show('Added "' + fav.label + '" to route', 'success');
        this.close();
    },

    commitRename: function(fav, newLabel) {
        const trimmed = (newLabel || '').trim();
        if (trimmed.length === 0) {
            window.Toast.show('Label cannot be empty.', 'warning');
            return;
        }
        if (trimmed === fav.label) {
            // No-op rename — just exit edit mode.
            this.editingRowId = null;
            this.renderRows();
            return;
        }

        window.TripWeather.Services.Favorites.rename(fav.id, trimmed)
            .then(function(updated) {
                // Patch the local snapshot from the response body so the row
                // re-renders without an extra GET.
                const idx = this.rows.findIndex(function(r) { return r.id === fav.id; });
                if (idx !== -1) this.rows[idx] = updated;
                this.editingRowId = null;
                this.renderRows();
                window.Toast.show('Renamed to "' + updated.label + '"', 'success');
            }.bind(this))
            .catch(function(err) {
                if (err.code === 'DUPLICATE_FAVORITE_LABEL') {
                    window.Toast.show(err.message, 'warning');
                } else {
                    window.Toast.show('Rename failed: ' + err.message, 'error');
                }
            });
    },

    handleDelete: function(fav) {
        const ui = window.TripWeather.Managers.UI;
        const performDelete = function() {
            window.TripWeather.Services.Favorites.remove(fav.id)
                .then(function() {
                    this.rows = this.rows.filter(function(r) { return r.id !== fav.id; });
                    this.renderRows();
                    window.Toast.show('Deleted "' + fav.label + '"', 'success');
                }.bind(this))
                .catch(function(err) {
                    window.Toast.show('Delete failed: ' + err.message, 'error');
                });
        }.bind(this);

        if (ui && typeof ui.showConfirm === 'function') {
            ui.showConfirm(
                'Delete "' + fav.label + '"? It can be restored from the admin console for 7 days.',
                performDelete,
                null,
                { title: 'Delete favorite', confirmLabel: 'Delete', danger: true });
        } else {
            // Defensive fallback if UIManager isn't initialised yet
            if (window.confirm('Delete "' + fav.label + '"?')) performDelete();
        }
    }
};
