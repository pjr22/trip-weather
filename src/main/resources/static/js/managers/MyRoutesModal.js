/**
 * MyRoutesModal — manager for the "My Routes" modal opened from the profile
 * menu. Phase 4 of FAVORITES_AND_ROUTE_MGMT.md.
 *
 * Scope:
 *  - Open from the profile menu (UIManager dispatches 'myRoutes' here)
 *  - Fetch GET /api/routes on each open → render rows (no client-side cache)
 *  - Per-row actions: Load (delegates to existing search-modal load flow),
 *    Rename (inline editor → PATCH /api/routes/{id}), Delete (confirm →
 *    DELETE /api/routes/{id})
 *  - Anonymous users see no entry point (UIManager hides the menu item)
 *
 * The plan called out a Distance column; it's deferred to a follow-up
 * because Route has no persisted total-distance field today, so every row
 * would render "—". When Route gains that field, the column slots in
 * without touching the modal API.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.MyRoutesModal = {

    initialized: false,
    rows: [],                // local snapshot for the modal's lifetime
    editingRowId: null,      // id of the row whose name is in inline-edit mode

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;

        this.setupModalCloseAffordances();
        this.subscribeAuthChanges();
    },

    subscribeAuthChanges: function() {
        // Mirrors FavoritesManagerModal: close the modal if the user logs
        // out while it's open (the menu item is hidden for anonymous, so
        // re-opening from logged-out is already blocked).
        const auth = window.TripWeather.Services.Auth;
        if (auth && typeof auth.onChange === 'function') {
            auth.onChange(function(user) {
                if (!user) this.close();
            }.bind(this));
        }
    },

    setupModalCloseAffordances: function() {
        const modal = document.getElementById('my-routes-modal');
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

        const searchInput = document.getElementById('my-routes-search-input');
        if (searchInput) {
            // Client-side filter over the in-memory snapshot — no per-keystroke
            // server round-trip. The server-side ?search= filter on
            // GET /api/routes is reserved for the (separate) Load Route flow
            // and future pagination.
            searchInput.addEventListener('input', this.renderRows.bind(this));
        }
    },

    open: function() {
        const auth = window.TripWeather.Services.Auth;
        if (!auth || !auth.getCurrentUser()) return;

        const modal = document.getElementById('my-routes-modal');
        if (!modal) return;
        modal.style.display = 'block';

        const searchInput = document.getElementById('my-routes-search-input');
        if (searchInput) searchInput.value = '';

        this.editingRowId = null;
        this.setStatus('Loading…');

        window.TripWeather.Services.RoutePersistence.listRoutes()
            .then(function(routes) {
                this.rows = routes || [];
                this.setStatus('');
                this.renderRows();
            }.bind(this))
            .catch(function(err) {
                this.rows = [];
                this.setStatus('Could not load routes: ' + (err.message || err));
                this.renderRows();
            }.bind(this));
    },

    close: function() {
        const modal = document.getElementById('my-routes-modal');
        if (modal) modal.style.display = 'none';
        this.editingRowId = null;
    },

    setStatus: function(text) {
        const el = document.getElementById('my-routes-modal-status');
        if (el) el.textContent = text || '';
    },

    // ---------------- Row rendering ----------------

    renderRows: function() {
        const tbody = document.getElementById('my-routes-modal-tbody');
        const emptyEl = document.getElementById('my-routes-modal-empty');
        if (!tbody) return;

        tbody.innerHTML = '';

        const searchInput = document.getElementById('my-routes-search-input');
        const q = (searchInput && searchInput.value || '').trim().toLowerCase();
        const filtered = q.length === 0
            ? this.rows
            : this.rows.filter(function(r) {
                return (r.name || '').toLowerCase().indexOf(q) !== -1;
            });

        if (filtered.length === 0) {
            if (emptyEl) {
                emptyEl.style.display = '';
                emptyEl.textContent = this.rows.length === 0
                    ? 'You haven\'t saved any routes yet.'
                    : 'No routes match your search.';
            }
            return;
        }
        if (emptyEl) emptyEl.style.display = 'none';

        filtered.forEach(function(route) {
            tbody.appendChild(this.buildRow(route));
        }.bind(this));
    },

    buildRow: function(route) {
        const tr = document.createElement('tr');
        tr.dataset.routeId = route.id;

        const nameTd = document.createElement('td');
        nameTd.className = 'favorites-cell-label';
        if (this.editingRowId === route.id) {
            const input = document.createElement('input');
            input.type = 'text';
            input.className = 'favorites-rename-input';
            input.value = route.name;
            input.maxLength = 255;
            input.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    this.commitRename(route, input.value);
                } else if (e.key === 'Escape') {
                    e.preventDefault();
                    this.editingRowId = null;
                    this.renderRows();
                }
            }.bind(this));
            nameTd.appendChild(input);
            setTimeout(function() { try { input.focus(); input.select(); } catch (_) {} }, 0);
        } else {
            nameTd.textContent = route.name;
        }

        const createdTd = document.createElement('td');
        createdTd.className = 'favorites-cell-location';
        createdTd.textContent = this.formatCreated(route.created);

        const countTd = document.createElement('td');
        countTd.className = 'my-routes-cell-count';
        countTd.textContent = String(route.waypointCount != null ? route.waypointCount : 0);

        const actionsTd = document.createElement('td');
        actionsTd.className = 'favorites-cell-actions';
        if (this.editingRowId === route.id) {
            const saveBtn = document.createElement('button');
            saveBtn.className = 'modal-btn primary small-btn';
            saveBtn.textContent = 'Save';
            saveBtn.addEventListener('click', function() {
                const input = nameTd.querySelector('input');
                this.commitRename(route, input ? input.value : route.name);
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
            actionsTd.appendChild(this.makeActionButton('Load', 'load-route',
                this.handleLoad.bind(this, route)));
            actionsTd.appendChild(this.makeActionButton('Rename', 'rename',
                function() {
                    this.editingRowId = route.id;
                    this.renderRows();
                }.bind(this)));
            actionsTd.appendChild(this.makeActionButton('Delete', 'delete-route',
                this.handleDelete.bind(this, route)));
        }

        tr.appendChild(nameTd);
        tr.appendChild(createdTd);
        tr.appendChild(countTd);
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

    formatCreated: function(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        if (isNaN(d.getTime())) return '';
        // Match the route-search modal's existing format so the two surfaces
        // present the same date shape.
        return d.toLocaleDateString();
    },

    // ---------------- Action handlers ----------------

    /**
     * Reuse the existing route-load flow from SearchManager. It already does
     * the loadRoute fetch, waypoint hydration, currentRoute state update,
     * and calculateRoute trigger — exactly what we want here, plus the call
     * to hideRouteSearchModal which is a no-op when that modal isn't open.
     */
    handleLoad: function(route) {
        this.close();
        const search = window.TripWeather.Managers.Search;
        if (search && typeof search.selectRouteSearchResult === 'function') {
            search.selectRouteSearchResult(route.id);
        } else {
            window.Toast.show('Load handler not available', 'error');
        }
    },

    commitRename: function(route, newName) {
        const trimmed = (newName || '').trim();
        if (trimmed.length === 0) {
            window.Toast.show('Route name cannot be empty.', 'warning');
            return;
        }
        if (trimmed === route.name) {
            // No-op rename — just exit edit mode.
            this.editingRowId = null;
            this.renderRows();
            return;
        }

        window.TripWeather.Services.RoutePersistence.renameRoute(route.id, trimmed)
            .then(function(updated) {
                const idx = this.rows.findIndex(function(r) { return r.id === route.id; });
                if (idx !== -1) this.rows[idx] = updated;
                this.editingRowId = null;
                this.renderRows();
                window.Toast.show('Renamed to "' + updated.name + '"', 'success');

                // If the renamed route is the one currently loaded in the
                // editor, update the header display so the two surfaces
                // don't disagree.
                const app = window.TripWeather.App;
                if (app && app.currentRoute && app.currentRoute.id === route.id) {
                    if (typeof app.setCurrentRouteName === 'function') {
                        app.setCurrentRouteName(updated.name);
                    }
                }
            }.bind(this))
            .catch(function(err) {
                if (err.status === 400) {
                    window.Toast.show('Invalid name — must be non-empty and ≤ 255 characters.', 'warning');
                } else if (err.status === 404) {
                    window.Toast.show('That route no longer exists.', 'warning');
                } else {
                    window.Toast.show('Rename failed: ' + (err.message || err), 'error');
                }
            });
    },

    handleDelete: function(route) {
        const ui = window.TripWeather.Managers.UI;
        const performDelete = function() {
            window.TripWeather.Services.RoutePersistence.deleteRoute(route.id)
                .then(function() {
                    this.rows = this.rows.filter(function(r) { return r.id !== route.id; });
                    this.renderRows();
                    window.Toast.show('Deleted "' + route.name + '"', 'success');

                    // If the deleted route was loaded in the editor, clear
                    // it back to a new-route state so the SPA doesn't keep
                    // a now-orphaned route id in memory.
                    const app = window.TripWeather.App;
                    if (app && app.currentRoute && app.currentRoute.id === route.id) {
                        if (typeof app.resetCurrentRoute === 'function') {
                            app.resetCurrentRoute();
                        }
                        const waypointMgr = window.TripWeather.Managers.Waypoint;
                        if (waypointMgr && typeof waypointMgr.clearAllWaypoints === 'function') {
                            waypointMgr.clearAllWaypoints();
                        }
                    }
                }.bind(this))
                .catch(function(err) {
                    window.Toast.show('Delete failed: ' + (err.message || err), 'error');
                });
        }.bind(this);

        if (ui && typeof ui.showConfirm === 'function') {
            ui.showConfirm(
                'Delete "' + route.name + '"? It can be restored from the admin console for 7 days.',
                performDelete,
                null,
                { title: 'Delete route', confirmLabel: 'Delete', danger: true });
        } else {
            if (window.confirm('Delete "' + route.name + '"?')) performDelete();
        }
    }
};
