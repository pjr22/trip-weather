/* Admin login bootstrap — POSTs to /api/admin/login and redirects to /admin/
 * on success. Phase 0 of ADMIN_CONSOLE.md.
 */
(function () {
    'use strict';

    var form = document.getElementById('login-form');
    var errorEl = document.getElementById('login-error');

    form.addEventListener('submit', function (event) {
        event.preventDefault();
        errorEl.textContent = '';

        var username = document.getElementById('username').value.trim();
        var password = document.getElementById('password').value;

        fetch('/api/admin/login', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: username, password: password })
        }).then(function (response) {
            if (response.ok) {
                window.location.href = '/admin/';
                return;
            }
            return response.json().then(function (body) {
                errorEl.textContent = (body && body.error)
                    ? body.error
                    : ('Login failed (HTTP ' + response.status + ').');
            }).catch(function () {
                errorEl.textContent = 'Login failed (HTTP ' + response.status + ').';
            });
        }).catch(function () {
            errorEl.textContent = 'Network error — please try again.';
        });
    });
})();
