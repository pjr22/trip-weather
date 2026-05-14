/* Phase 1 of ADMIN_CONSOLE.md — Routes management view.
 *
 * Renders search + owner filter + "Show deleted" toggle + sortable paginated
 * table. Per-row Delete (soft-delete) and Restore actions; "Trigger cleanup"
 * button at the top fires the cleanup job's two-stage purge asynchronously.
 *
 * Vanilla JS, no framework. Owns its own state (filter / page / sort) inside
 * the closure; AdminApp.js calls render(rootEl) on every hash change but
 * since the hash doesn't change while we're on #routes, the state survives
 * within one tab session and resets when the operator navigates away and back.
 */
(function () {
    'use strict';

    var DEFAULT_PAGE_SIZE = 25;

    /** Module-scoped view state. Mutates on filter / sort / page changes. */
    var state = {
        q: '',
        owner: '',           // '' | 'USER' | 'GUEST'
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
            + '<h2 id="routes-title">Routes</h2>'
            + '<div class="routes-toolbar">'
            +   '<input type="search" id="routes-q" placeholder="Search name or owner email…" />'
            +   '<select id="routes-owner">'
            +     '<option value="">All owners</option>'
            +     '<option value="USER">Users</option>'
            +     '<option value="GUEST">Guest</option>'
            +   '</select>'
            +   '<select id="routes-deleted">'
            +     '<option value="false">Active only</option>'
            +     '<option value="true">Deleted only</option>'
            +     '<option value="all">All (incl. deleted)</option>'
            +   '</select>'
            +   '<button id="routes-trigger-cleanup">Trigger cleanup</button>'
            + '</div>'
            + '<div id="routes-table-wrap"></div>';
    }

    function wireControls() {
        var qEl = document.getElementById('routes-q');
        var ownerEl = document.getElementById('routes-owner');
        var deletedEl = document.getElementById('routes-deleted');
        var cleanupBtn = document.getElementById('routes-trigger-cleanup');

        qEl.value = state.q;
        ownerEl.value = state.owner;
        deletedEl.value = state.deleted;

        // Debounced search; same pattern the public SPA uses for its
        // forward-geocode box. 250 ms is short enough to feel responsive
        // and long enough to skip every keystroke during a long query.
        var searchTimer = null;
        qEl.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function () {
                state.q = qEl.value;
                state.page = 0;
                loadAndRender();
            }, 250);
        });

        ownerEl.addEventListener('change', function () {
            state.owner = ownerEl.value;
            state.page = 0;
            loadAndRender();
        });
        deletedEl.addEventListener('change', function () {
            state.deleted = deletedEl.value;
            state.page = 0;
            loadAndRender();
        });

        cleanupBtn.addEventListener('click', function () {
            AdminUI.confirm({
                title: 'Trigger cleanup',
                message:
                    'Run the two-stage cleanup now?\n\n' +
                    'Stage 1 soft-deletes guest routes past the retention window.\n' +
                    'Stage 2 hard-deletes any soft-deleted route past the grace window.\n\n' +
                    'Progress is recorded in the Data tab’s loader history.',
                confirmLabel: 'Run cleanup'
            }).then(function (ok) {
                if (!ok) return;
                cleanupBtn.disabled = true;
                // Phase 2: cleanup is now one of several loaders managed
                // through /api/admin/loaders. The Data tab is the durable
                // place to watch progress; we still refresh the list here
                // so soft-deleted rows that the cleanup affects show up.
                AdminApi.post('/api/admin/loaders/guest-route-cleanup/trigger').then(function () {
                    showMessage('Cleanup triggered. See Data tab for progress.', 'ok');
                    // Stagger reloads so soft-deleted rows appear quickly even
                    // when the cleanup completes in well under 1.5 s. Routes
                    // tab doesn't poll loaders directly — these reloads pick
                    // up the route-list changes the cleanup made.
                    setTimeout(function () { cleanupBtn.disabled = false; loadAndRender(); }, 600);
                    setTimeout(loadAndRender, 1500);
                    setTimeout(loadAndRender, 3000);
                }).catch(function (err) {
                    cleanupBtn.disabled = false;
                    if (err && err.status === 409) {
                        showMessage('Cleanup is already running — see Data tab.', 'err');
                    } else {
                        showMessage('Cleanup trigger failed: ' + (err && err.message), 'err');
                    }
                });
            });
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
        if (state.owner) params.set('owner', state.owner);
        params.set('deleted', state.deleted);
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', state.sort);

        AdminApi.get('/api/admin/routes?' + params.toString()).then(function (page) {
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
        // Move the total count into the page title (e.g. "Routes (12)") so it
        // isn't dangling below the table as a one-line footer. The pagination
        // footer below still carries "Page X of Y (Z total)" when there are
        // multiple pages — that's navigation context, not a redundant count.
        // Mirror of the same treatment in UsersView.
        var titleEl = document.getElementById('routes-title');
        if (titleEl) {
            titleEl.textContent = 'Routes (' + page.totalElements + ')';
        }

        var wrap = document.getElementById('routes-table-wrap');
        if (!page.content || page.content.length === 0) {
            wrap.innerHTML = '<div class="routes-empty">No routes match the current filters.</div>';
            return;
        }
        var html = ''
            + '<table class="admin-table">'
            +   '<thead><tr>'
            +     sortHeader('Name', 'name')
            +     '<th>Owner</th>'
            +     '<th>Kind</th>'
            +     '<th>Waypoints</th>'
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
                onRowAction(btn.getAttribute('data-action'), btn.getAttribute('data-id'));
            });
        });
        var prev = wrap.querySelector('[data-page="prev"]');
        var next = wrap.querySelector('[data-page="next"]');
        if (prev) prev.addEventListener('click', function () { gotoPage(state.page - 1); });
        if (next) next.addEventListener('click', function () { gotoPage(state.page + 1); });
    }

    function rowHtml(route) {
        var deleted = route.deletedAt != null;
        var rowCls = deleted ? ' class="routes-row-deleted"' : '';
        var actionBtn = deleted
            ? '<button class="primary" data-action="restore" data-id="' + escAttr(route.id) + '">Restore</button>'
            : '<button data-action="delete" data-id="' + escAttr(route.id) + '">Delete</button>';
        return ''
            + '<tr' + rowCls + '>'
            +   '<td>' + esc(route.name) + '</td>'
            +   '<td>' + esc(route.ownerEmail) + '</td>'
            +   '<td>' + esc(route.ownerKind) + '</td>'
            +   '<td>' + route.waypointCount + '</td>'
            +   '<td>' + formatTimestamp(route.created) + '</td>'
            +   '<td>' + formatTimestamp(route.deletedAt) + '</td>'
            +   '<td>' + actionBtn + '</td>'
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
        // Single-page case has no footer at all — the count lives in the h2
        // title now, and there are no Prev/Next controls to display.
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
            // Default to descending on first click of a new column —
            // most timestamp queries want newest first.
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

    function onRowAction(action, id) {
        if (action === 'delete') {
            AdminUI.confirm({
                title: 'Soft-delete route',
                message:
                    'Soft-delete this route?\n\n' +
                    'It can be restored from the "Deleted only" filter until ' +
                    'the cleanup job hard-deletes it past the grace window.',
                confirmLabel: 'Delete',
                danger: true
            }).then(function (ok) {
                if (!ok) return;
                AdminApi.del('/api/admin/routes/' + encodeURIComponent(id)).then(function () {
                    showMessage('Route soft-deleted.', 'ok');
                    loadAndRender();
                }).catch(function (err) {
                    showMessage('Soft-delete failed: ' + (err && err.message), 'err');
                });
            });
        } else if (action === 'restore') {
            AdminApi.post('/api/admin/routes/' + encodeURIComponent(id) + '/restore').then(function () {
                showMessage('Route restored.', 'ok');
                loadAndRender();
            }).catch(function (err) {
                showMessage('Restore failed: ' + (err && err.message), 'err');
            });
        }
    }

    /**
     * Delegate to the shared floating toast (window.Toast — same code the
     * main SPA uses). The legacy {kind: 'ok' | 'err'} convention used by
     * this view is mapped to Toast's {type: 'success' | 'error'}. Calls
     * without a kind (the previous "Loading…" inline indicator pattern)
     * are dropped — those don't translate cleanly to a floating toast and
     * the table re-rendering is already feedback enough.
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
    window.AdminViews.routes = render;
})();
