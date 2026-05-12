/* Phase 4 of ADMIN_CONSOLE.md — Users management view.
 *
 * Renders search + enabled-filter + sortable paginated table. Per-row
 * Enable/Disable / Force-verify / Delete buttons. Destructive actions
 * (Force-verify, Delete) go through AdminUI.confirm; Delete additionally
 * shows the user's active + soft-deleted route counts in the confirm
 * message so the operator knows exactly what's about to cascade.
 *
 * Vanilla JS, no framework. Same shape as RoutesView so the two stay
 * visually and behaviourally consistent (one toolbar pattern across the
 * console, one pagination pattern, one toast pattern).
 */
(function () {
    'use strict';

    var DEFAULT_PAGE_SIZE = 25;

    var state = {
        q: '',
        enabled: '',        // '' (all) | 'true' | 'false'
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
            + '<h2 id="users-title">Users</h2>'
            + '<div class="routes-toolbar">'
            +   '<input type="search" id="users-q" placeholder="Search email or name…" />'
            +   '<select id="users-enabled">'
            +     '<option value="">All accounts</option>'
            +     '<option value="true">Enabled only</option>'
            +     '<option value="false">Disabled only</option>'
            +   '</select>'
            + '</div>'
            + '<div id="users-table-wrap"></div>';
    }

    function wireControls() {
        var qEl = document.getElementById('users-q');
        var enabledEl = document.getElementById('users-enabled');

        qEl.value = state.q;
        enabledEl.value = state.enabled;

        // 250ms debounce — same as RoutesView (and the main SPA's forward
        // geocode box).
        var searchTimer = null;
        qEl.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function () {
                state.q = qEl.value;
                state.page = 0;
                loadAndRender();
            }, 250);
        });

        enabledEl.addEventListener('change', function () {
            state.enabled = enabledEl.value;
            state.page = 0;
            loadAndRender();
        });
    }

    function loadAndRender() {
        if (state.loading) return;
        state.loading = true;

        var params = new URLSearchParams();
        if (state.q) params.set('q', state.q);
        if (state.enabled) params.set('enabled', state.enabled);
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', state.sort);

        AdminApi.get('/api/admin/users?' + params.toString()).then(function (page) {
            state.lastPage = page;
            state.loading = false;
            renderTable(page);
        }).catch(function (err) {
            state.loading = false;
            showMessage('Failed to load users: ' + (err && err.message), 'err');
        });
    }

    function renderTable(page) {
        // Move the total count into the page title (e.g. "Users (12)") so
        // it isn't dangling below the table as a one-line footer. The
        // pagination footer below still carries "Page X of Y (Z total)"
        // when there are multiple pages — that's navigation context, not
        // a redundant count.
        var titleEl = document.getElementById('users-title');
        if (titleEl) {
            titleEl.textContent = 'Users (' + page.totalElements + ')';
        }

        var wrap = document.getElementById('users-table-wrap');
        if (!page.content || page.content.length === 0) {
            wrap.innerHTML = '<div class="routes-empty">No users match the current filters.</div>';
            return;
        }
        var html = ''
            + '<table class="admin-table">'
            +   '<thead><tr>'
            +     sortHeader('Email', 'email')
            +     sortHeader('Name', 'name')
            +     sortHeader('Enabled', 'enabled')
            +     sortHeader('Created', 'created')
            +     '<th>Routes</th>'
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
                applySort(th.getAttribute('data-sort'));
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

    function rowHtml(user) {
        var rowCls = user.enabled ? '' : ' class="users-row-disabled"';
        // Pending-verification badge shown next to the email so the operator
        // can spot stuck signups at a glance. Force-verify is the relevant
        // action; the badge is the cue.
        var pendingBadge = user.hasPendingVerification
            ? ' <span class="users-pending-badge" title="Email verification pending">pending</span>'
            : '';
        var enabledText = user.enabled ? 'yes' : 'no';
        var toggleBtn = user.enabled
            ? '<button data-action="disable" data-id="' + escAttr(user.id) + '">Disable</button>'
            : '<button data-action="enable" data-id="' + escAttr(user.id) + '" class="primary">Enable</button>';
        var verifyBtn = '<button data-action="force-verify" data-id="' + escAttr(user.id) + '">Force-verify</button>';
        var deleteBtn = '<button data-action="delete" data-id="' + escAttr(user.id) + '" class="danger">Delete</button>';
        return ''
            + '<tr' + rowCls + '>'
            +   '<td>' + esc(user.email) + pendingBadge + '</td>'
            +   '<td>' + esc(user.name) + '</td>'
            +   '<td>' + enabledText + '</td>'
            +   '<td>' + formatTimestamp(user.created) + '</td>'
            +   '<td>' + user.routeCount + '</td>'
            +   '<td>' + toggleBtn + ' ' + verifyBtn + ' ' + deleteBtn + '</td>'
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
        // Single-page case has no footer at all — the count lives in the
        // h2 title, and there are no Prev/Next controls to display.
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
            // Default to descending on first click — matches RoutesView; for
            // timestamps and counts that's "newest/biggest first," for
            // strings (email/name) it's arbitrary but consistent.
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
        var user = findUser(id);
        if (!user) return;

        if (action === 'enable')        return doEnable(user);
        if (action === 'disable')       return doDisable(user);
        if (action === 'force-verify')  return doForceVerify(user);
        if (action === 'delete')        return doDelete(user);
    }

    function findUser(id) {
        if (!state.lastPage) return null;
        for (var i = 0; i < state.lastPage.content.length; i++) {
            if (state.lastPage.content[i].id === id) return state.lastPage.content[i];
        }
        return null;
    }

    function doEnable(user) {
        // Enable is non-destructive; no confirm. The operator's intent is
        // clear from the button label, and the action is trivially reversible.
        AdminApi.post('/api/admin/users/' + encodeURIComponent(user.id) + '/enable')
            .then(function () {
                showMessage('Enabled ' + user.email + '.', 'ok');
                loadAndRender();
            })
            .catch(function (err) {
                showMessage('Enable failed: ' + (err && err.message), 'err');
            });
    }

    function doDisable(user) {
        AdminUI.confirm({
            title: 'Disable account',
            message:
                'Disable ' + user.email + '?\n\n' +
                'The user will no longer be able to log in. Their saved ' +
                'routes are untouched. Re-enabling is one click.',
            confirmLabel: 'Disable'
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.post('/api/admin/users/' + encodeURIComponent(user.id) + '/disable')
                .then(function () {
                    showMessage('Disabled ' + user.email + '.', 'ok');
                    loadAndRender();
                })
                .catch(function (err) {
                    showMessage('Disable failed: ' + (err && err.message), 'err');
                });
        });
    }

    function doForceVerify(user) {
        AdminUI.confirm({
            title: 'Force-verify account',
            message:
                'Force-verify ' + user.email + '?\n\n' +
                'The account will be enabled, and every still-open email ' +
                'verification AND password-reset token for this user will be ' +
                'consumed. Use this when a user is stuck because their ' +
                'verification email never arrived, or to clear out stale ' +
                'reset tokens after an unstuck signup.',
            confirmLabel: 'Force-verify'
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.post('/api/admin/users/' + encodeURIComponent(user.id) + '/force-verify')
                .then(function () {
                    showMessage('Force-verified ' + user.email + '.', 'ok');
                    loadAndRender();
                })
                .catch(function (err) {
                    showMessage('Force-verify failed: ' + (err && err.message), 'err');
                });
        });
    }

    function doDelete(user) {
        // The confirm message shows the user's active route count up-front
        // so the operator sees what's about to cascade. Soft-deleted routes
        // aren't surfaced here (we don't have a count for them on the row)
        // but the toast on success reports both — sufficient blast-radius
        // disclosure for a double-confirm flow.
        AdminUI.confirm({
            title: 'Hard-delete user',
            message:
                'Permanently delete ' + user.email + '?\n\n' +
                'This deletes the user account AND cascades to ' + user.routeCount +
                    ' active route' + (user.routeCount === 1 ? '' : 's') +
                    ' plus any soft-deleted routes still in the recycle window.\n\n' +
                'Pending verifications and password-resets are also dropped. ' +
                'This cannot be undone.',
            confirmLabel: 'Delete user',
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.del('/api/admin/users/' + encodeURIComponent(user.id))
                .then(function (result) {
                    var active = (result && result.activeRoutesDeleted) || 0;
                    var softDeleted = (result && result.softDeletedRoutesDeleted) || 0;
                    var detail = active + ' active route' + (active === 1 ? '' : 's');
                    if (softDeleted > 0) {
                        detail += ', ' + softDeleted + ' soft-deleted';
                    }
                    showMessage('Deleted ' + user.email + ' (cascaded ' + detail + ').', 'ok');
                    loadAndRender();
                })
                .catch(function (err) {
                    showMessage('Delete failed: ' + (err && err.message), 'err');
                });
        });
    }

    /** Same legacy {ok/err} -> {success/error} mapping the other views use. */
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

    function escAttr(s) { return esc(s); }

    window.AdminViews = window.AdminViews || {};
    window.AdminViews.users = render;
})();
