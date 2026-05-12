/* Admin console boot — verifies an active admin session, renders the shell
 * header, and dispatches to the active view based on the URL hash.
 *
 * Phase 1 of ADMIN_CONSOLE.md: hash-driven view dispatch. Phase 0 only had
 * a placeholder. Each view registers itself on window.AdminViews under a
 * key matching the nav link's data-view attribute and the hash; views that
 * haven't shipped yet fall through to a generic placeholder.
 */
(function () {
    'use strict';

    var DEFAULT_VIEW = 'routes';
    var rootEl = null;

    function currentView() {
        var hash = window.location.hash.replace(/^#/, '');
        return hash || DEFAULT_VIEW;
    }

    function setActiveNav(view) {
        var links = document.querySelectorAll('.shell-nav a');
        for (var i = 0; i < links.length; i++) {
            if (links[i].getAttribute('data-view') === view) {
                links[i].classList.add('active');
            } else {
                links[i].classList.remove('active');
            }
        }
    }

    function renderPlaceholder(view) {
        // Every phase view is now shipped (routes, data, metrics, users).
        // The placeholder map stays as a safety net for any future nav entry
        // added before its view JS lands; for unknown hashes, fall back to
        // a blank pane rather than a stale "coming in phase N" sign.
        rootEl.innerHTML = '';
    }

    function renderActiveView() {
        var view = currentView();
        setActiveNav(view);

        var registry = window.AdminViews || {};
        var renderer = registry[view];
        if (typeof renderer === 'function') {
            renderer(rootEl);
        } else {
            renderPlaceholder(view);
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        rootEl = document.getElementById('view-root');
        var whoEl = document.getElementById('admin-who');
        var logoutBtn = document.getElementById('admin-logout');

        AdminApi.get('/api/admin/me').then(function (me) {
            whoEl.textContent = 'Signed in as ' + me.username;
        });

        logoutBtn.addEventListener('click', function () {
            AdminApi.post('/api/admin/logout').then(function () {
                window.location.href = '/admin/login.html';
            });
        });

        window.addEventListener('hashchange', renderActiveView);
        renderActiveView();
    });
})();
