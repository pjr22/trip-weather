/* Tiny fetch wrapper for admin XHRs. On 401, redirects to the login page so
 * an expired session lands the operator back at the form rather than showing
 * a broken admin page. Phase 0 of ADMIN_CONSOLE.md.
 */
window.AdminApi = (function () {
    'use strict';

    function request(method, path, body) {
        var init = {
            method: method,
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        };
        if (body !== undefined) {
            init.headers['Content-Type'] = 'application/json';
            init.body = JSON.stringify(body);
        }
        return fetch(path, init).then(function (response) {
            if (response.status === 401) {
                window.location.href = '/admin/login.html';
                return Promise.reject(new Error('Unauthenticated'));
            }
            if (!response.ok) {
                return response.text().then(function (text) {
                    var err = new Error('HTTP ' + response.status);
                    err.status = response.status;
                    err.body = text;
                    throw err;
                });
            }
            if (response.status === 204) {
                return null;
            }
            var contentType = response.headers.get('Content-Type') || '';
            return contentType.indexOf('application/json') === 0
                ? response.json()
                : response.text();
        });
    }

    return {
        get: function (path) { return request('GET', path); },
        post: function (path, body) { return request('POST', path, body); },
        del:  function (path) { return request('DELETE', path); }
    };
})();
