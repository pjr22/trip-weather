/* Phase 5 of FAVORITES_AND_ROUTE_MGMT.md — Favorites management view.
 *
 * Renders search + "Show deleted" toggle + sortable paginated table.
 * Per-row Delete (soft-delete) on active rows; per-row Restore + Purge
 * (hard-delete) on deleted rows.
 *
 * Vanilla JS, no framework. Mirrors the structure of RoutesView.js so the
 * two admin views feel like one. State lives in module-scope and resets
 * when the operator navigates away and back, same as Routes.
 */
(function () {
    'use strict';

    var DEFAULT_PAGE_SIZE = 25;

    /** Module-scoped view state. Mutates on filter / sort / page changes. */
    var state = {
        q: '',
        deleted: 'false',    // 'false' | 'true' | 'all'
        page: 0,
        size: DEFAULT_PAGE_SIZE,
        sort: 'created,desc',
        loading: false,
        lastPage: null
    };

    var rootEl = null;

    function render(root) {
        rootEl = root;
        rootEl.innerHTML = templateShell();
        wireControls();
        loadAndRender();
    }

    function templateShell() {
        return ''
            + '<h2 id="favorites-title">Favorites</h2>'
            + '<div class="routes-toolbar">'
            +   '<input type="search" id="favorites-q" placeholder="Search label or owner email…" />'
            +   '<select id="favorites-deleted">'
            +     '<option value="false">Active only</option>'
            +     '<option value="true">Deleted only</option>'
            +     '<option value="all">All (incl. deleted)</option>'
            +   '</select>'
            + '</div>'
            + '<div id="favorites-table-wrap"></div>';
    }

    function wireControls() {
        var qEl = document.getElementById('favorites-q');
        var deletedEl = document.getElementById('favorites-deleted');

        qEl.value = state.q;
        deletedEl.value = state.deleted;

        var searchTimer = null;
        qEl.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function () {
                state.q = qEl.value;
                state.page = 0;
                loadAndRender();
            }, 250);
        });

        deletedEl.addEventListener('change', function () {
            state.deleted = deletedEl.value;
            state.page = 0;
            loadAndRender();
        });
    }

    function loadAndRender() {
        if (state.loading) {
            return;
        }
        state.loading = true;
        showMessage('Loading…');

        var params = new URLSearchParams();
        if (state.q) params.set('q', state.q);
        params.set('deleted', state.deleted);
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', state.sort);

        AdminApi.get('/api/admin/favorites?' + params.toString()).then(function (page) {
            state.lastPage = page;
            state.loading = false;
            showMessage('');
            renderTable(page);
        }).catch(function (err) {
            state.loading = false;
            showMessage('Failed to load: ' + (err && err.message), 'err');
        });
    }

    function renderTable(page) {
        var titleEl = document.getElementById('favorites-title');
        if (titleEl) {
            titleEl.textContent = 'Favorites (' + page.totalElements + ')';
        }

        var wrap = document.getElementById('favorites-table-wrap');
        if (!page.content || page.content.length === 0) {
            wrap.innerHTML = '<div class="routes-empty">No favorites match the current filters.</div>';
            return;
        }
        var html = ''
            + '<table class="admin-table">'
            +   '<thead><tr>'
            +     sortHeader('Label', 'label')
            +     '<th>Owner</th>'
            +     '<th>Address</th>'
            +     '<th>Lat, Lon</th>'
            +     sortHeader('Created', 'created')
            +     sortHeader('Deleted at', 'deletedAt')
            +     '<th>Actions</th>'
            +   '</tr></thead>'
            +   '<tbody>'
            +     page.content.map(rowHtml).join('')
            +   '</tbody>'
            + '</table>'
            + paginationHtml(page);
        wrap.innerHTML = html;

        Array.prototype.forEach.call(wrap.querySelectorAll('th[data-sort]'), function (th) {
            th.addEventListener('click', function () {
                var field = th.getAttribute('data-sort');
                applySort(field);
            });
        });
        Array.prototype.forEach.call(wrap.querySelectorAll('button[data-action]'), function (btn) {
            btn.addEventListener('click', function () {
                onRowAction(
                    btn.getAttribute('data-action'),
                    btn.getAttribute('data-id'),
                    btn.getAttribute('data-label'));
            });
        });
        var prev = wrap.querySelector('[data-page="prev"]');
        var next = wrap.querySelector('[data-page="next"]');
        if (prev) prev.addEventListener('click', function () { gotoPage(state.page - 1); });
        if (next) next.addEventListener('click', function () { gotoPage(state.page + 1); });
    }

    function rowHtml(fav) {
        var deleted = fav.deletedAt != null;
        var rowCls = deleted ? ' class="routes-row-deleted"' : '';
        // Active rows expose only Delete (soft-delete). Deleted rows expose
        // Restore + Purge — surfacing the destructive hard-delete only once
        // the row is already deleted keeps the safety net intentional.
        var actions;
        if (deleted) {
            actions = ''
                + '<button class="primary" data-action="restore" data-id="' + escAttr(fav.id) + '">Restore</button>'
                + ' '
                + '<button data-action="purge" data-id="' + escAttr(fav.id)
                + '" data-label="' + escAttr(fav.label) + '">Purge</button>';
        } else {
            actions = '<button data-action="delete" data-id="' + escAttr(fav.id) + '">Delete</button>';
        }
        return ''
            + '<tr' + rowCls + '>'
            +   '<td>' + esc(fav.label) + '</td>'
            +   '<td>' + esc(fav.ownerEmail) + '</td>'
            +   '<td>' + esc(fav.locationName) + '</td>'
            +   '<td>' + formatCoord(fav.latitude, fav.longitude) + '</td>'
            +   '<td>' + formatTimestamp(fav.created) + '</td>'
            +   '<td>' + formatTimestamp(fav.deletedAt) + '</td>'
            +   '<td>' + actions + '</td>'
            + '</tr>';
    }

    function sortHeader(label, field) {
        var parts = state.sort.split(',');
        var arrow = '';
        if (parts[0] === field) {
            arrow = parts[1] === 'asc' ? ' ▲' : ' ▼';
        }
        return '<th data-sort="' + field + '" class="sortable">' + label + arrow + '</th>';
    }

    function paginationHtml(page) {
        if (page.totalPages <= 1) {
            return '';
        }
        var prevDisabled = page.page <= 0 ? ' disabled' : '';
        var nextDisabled = page.page >= page.totalPages - 1 ? ' disabled' : '';
        return ''
            + '<div class="routes-pagination">'
            +   '<button data-page="prev"' + prevDisabled + '>‹ Prev</button>'
            +   '<span>Page ' + (page.page + 1) + ' of ' + page.totalPages
            +     ' (' + page.totalElements + ' total)</span>'
            +   '<button data-page="next"' + nextDisabled + '>Next ›</button>'
            + '</div>';
    }

    function applySort(field) {
        var parts = state.sort.split(',');
        if (parts[0] === field) {
            state.sort = field + ',' + (parts[1] === 'asc' ? 'desc' : 'asc');
        } else {
            state.sort = field + ',desc';
        }
        loadAndRender();
    }

    function gotoPage(page) {
        if (page < 0) return;
        if (state.lastPage && page >= state.lastPage.totalPages) return;
        state.page = page;
        loadAndRender();
    }

    function onRowAction(action, id, label) {
        if (action === 'delete') {
            // Soft-delete uses POST /{id}/soft-delete — the controller deliberately
            // reserves DELETE for the irreversible hard-delete below.
            AdminUI.confirm({
                title: 'Soft-delete favorite',
                message:
                    'Soft-delete this favorite?\n\n' +
                    'It can be restored from the "Deleted only" filter until ' +
                    'the cleanup job hard-deletes it past the grace window.',
                confirmLabel: 'Delete',
                danger: true
            }).then(function (ok) {
                if (!ok) return;
                AdminApi.post('/api/admin/favorites/' + encodeURIComponent(id) + '/soft-delete').then(function () {
                    showMessage('Favorite soft-deleted.', 'ok');
                    loadAndRender();
                }).catch(function (err) {
                    showMessage('Soft-delete failed: ' + (err && err.message), 'err');
                });
            });
        } else if (action === 'restore') {
            AdminApi.post('/api/admin/favorites/' + encodeURIComponent(id) + '/restore').then(function () {
                showMessage('Favorite restored.', 'ok');
                loadAndRender();
            }).catch(function (err) {
                showMessage('Restore failed: ' + (err && err.message), 'err');
            });
        } else if (action === 'purge') {
            AdminUI.confirm({
                title: 'Permanently delete favorite',
                message:
                    'Permanently delete "' + (label || 'this favorite') + '"?\n\n' +
                    'This bypasses the soft-delete grace window — the row is gone immediately and CANNOT be restored.',
                confirmLabel: 'Purge',
                danger: true
            }).then(function (ok) {
                if (!ok) return;
                AdminApi.del('/api/admin/favorites/' + encodeURIComponent(id)).then(function () {
                    showMessage('Favorite purged.', 'ok');
                    loadAndRender();
                }).catch(function (err) {
                    showMessage('Purge failed: ' + (err && err.message), 'err');
                });
            });
        }
    }

    /**
     * Delegate to the shared floating toast (window.Toast). Same conventions
     * as RoutesView — {kind: 'ok' | 'err'} maps to Toast's
     * {type: 'success' | 'error'}; loading-indicator strings without a kind
     * are dropped because the table re-rendering is feedback enough.
     */
    function showMessage(text, kind) {
        if (!text || !window.Toast) return;
        var type;
        if (kind === 'ok')       type = 'success';
        else if (kind === 'err') type = 'error';
        else                     return;
        window.Toast.show(text, type);
    }

    function formatTimestamp(value) {
        if (value == null) return '';
        var d = new Date(value);
        if (isNaN(d.getTime())) return esc(String(value));
        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
             + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function formatCoord(lat, lon) {
        if (lat == null || lon == null) return '';
        return Number(lat).toFixed(5) + ', ' + Number(lon).toFixed(5);
    }

    function esc(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function escAttr(s) {
        return esc(s);
    }

    window.AdminViews = window.AdminViews || {};
    window.AdminViews.favorites = render;
})();
