/**
 * AuthService — current-user state and auth actions for the SPA.
 *
 * Holds the cached current-user value, broadcasts changes to listeners (used by
 * UIManager to re-render the profile menu), and wraps the /api/auth/* endpoints
 * so callers don't have to remember CSRF wiring.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.Auth = {

    currentUser: null,
    initialized: false,
    listeners: [],

    /**
     * Fetch the current session's user (or null) and cache it. Safe to call
     * more than once; it's also called after login/logout to refresh state.
     * @returns {Promise<object|null>} resolved user or null
     */
    init: async function() {
        try {
            const body = await this._getJson('/api/auth/me');
            this.setCurrentUser(body && body.user ? body.user : null);
            return this.currentUser;
        } catch (err) {
            console.warn('AuthService: failed to load current user', err);
            this.setCurrentUser(null);
            return null;
        } finally {
            this.initialized = true;
        }
    },

    /**
     * Sign up. The server always returns 201 with the same message regardless
     * of whether the email was new or already registered (anti-enumeration).
     * Callers should show "check your email" either way.
     */
    signup: async function(email, password, displayName) {
        return this._postJson('/api/auth/signup', { email, password, displayName });
    },

    /**
     * Submit a verification token. On success the server creates a session and
     * returns the now-authenticated user; AuthService caches it and broadcasts.
     */
    verify: async function(token) {
        const body = await this._postJson('/api/auth/verify', { token });
        if (body && body.user) {
            this.setCurrentUser(body.user);
        }
        return body;
    },

    resendVerification: async function(email) {
        return this._postJson('/api/auth/resend-verification', { email });
    },

    /**
     * Log in with email + password. On success the server creates a session
     * and returns the user; AuthService caches it and broadcasts.
     * Errors are thrown with .status and .body (per request() contract); the
     * caller (LoginModal) inspects .body.code === 'EMAIL_NOT_VERIFIED' to
     * show the resend-verification offer.
     *
     * @param {string} email
     * @param {string} password
     * @param {boolean} [rememberMe=false] — when true the server issues a
     *     persistent-token cookie that survives browser restart for 30 days
     */
    login: async function(email, password, rememberMe) {
        const body = await this._postJson('/api/auth/login', {
            email, password, rememberMe: !!rememberMe
        });
        if (body && body.user) {
            this.setCurrentUser(body.user);
        }
        return body;
    },

    logout: async function() {
        try {
            await this._postJson('/api/auth/logout', null);
        } finally {
            // Even if the server call failed, drop the cached user so the UI
            // shows logged-out state. The next /me call will reconcile.
            this.setCurrentUser(null);
        }
    },

    /**
     * Always returns 200 server-side regardless of whether the address is
     * registered, so callers should treat success as "if there was an account,
     * we sent the link."
     */
    forgotPassword: async function(email) {
        return this._postJson('/api/auth/forgot-password', { email });
    },

    /**
     * Submit a reset-password token along with the new password. On success
     * the server clears any current session — the SPA should then send the
     * user to the Login modal to re-authenticate with their new password.
     */
    resetPassword: async function(token, newPassword) {
        const body = await this._postJson('/api/auth/reset-password', {
            token, newPassword
        });
        // Server invalidated the session and any persistent cookie; drop the
        // cached user so the UI updates immediately.
        this.setCurrentUser(null);
        return body;
    },

    /**
     * Change the current user's password. The current session continues —
     * only OTHER browsers' remember-me cookies stop working.
     */
    changePassword: async function(currentPassword, newPassword) {
        return this._postJson('/api/auth/change-password', {
            currentPassword, newPassword
        });
    },

    /**
     * Delete the current account. Server cascades route + waypoint deletes
     * and invalidates the session; we drop the cached user so the UI
     * snaps back to anonymous immediately.
     */
    deleteAccount: async function(currentPassword) {
        try {
            return await this._postJson('/api/auth/delete-account', { currentPassword });
        } finally {
            this.setCurrentUser(null);
        }
    },

    isAuthenticated: function() {
        return this.currentUser !== null;
    },

    getCurrentUser: function() {
        return this.currentUser;
    },

    /**
     * Subscribe to current-user changes. Listener is called with the new user
     * (or null) whenever the cached value changes.
     */
    onChange: function(listener) {
        if (typeof listener === 'function') {
            this.listeners.push(listener);
        }
    },

    setCurrentUser: function(user) {
        const changed = JSON.stringify(this.currentUser) !== JSON.stringify(user);
        this.currentUser = user;
        if (changed) {
            this.listeners.forEach(function(fn) {
                try { fn(user); } catch (e) { console.warn('AuthService listener threw', e); }
            });
        }
    },

    _getJson: async function(url) {
        const response = await fetch(url, {
            method: 'GET',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            credentials: 'same-origin'
        });
        if (!response.ok) {
            throw await this._buildError(response);
        }
        return response.json();
    },

    _postJson: async function(url, payload) {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': window.TripWeather.Utils.Helpers.getCsrfToken()
            },
            credentials: 'same-origin',
            body: payload === null ? undefined : JSON.stringify(payload)
        });
        if (!response.ok) {
            throw await this._buildError(response);
        }
        // 204 No Content has empty body; .json() would throw.
        if (response.status === 204) {
            return null;
        }
        return response.json();
    },

    _buildError: async function(response) {
        let body = null;
        try { body = await response.json(); } catch (_) { /* ignore */ }
        const message = (body && (body.error || body.message)) || ('HTTP ' + response.status);
        const err = new Error(message);
        err.status = response.status;
        err.body = body;
        return err;
    }
};
