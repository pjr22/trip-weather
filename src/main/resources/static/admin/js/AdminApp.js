/* Admin console boot — verifies an active admin session, renders the shell
 * header, and wires the logout button. Section content is added in later
 * phases of ADMIN_CONSOLE.md.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
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
    });
})();
