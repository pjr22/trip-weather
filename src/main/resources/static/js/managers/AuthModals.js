/**
 * AuthModals — Signup / Login / Forgot- / Reset- / Change-password and
 * Delete-account modal flows.
 *
 * Phase 2 shipped signup + login. Phase 4 adds forgot/reset, change-password,
 * delete-account, plus the "Stay logged in" checkbox on Login.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.AuthModals = {

    initialized: false,

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;

        this._wireDismissals('signup-modal');
        this._wireDismissals('login-modal');
        this._wireDismissals('forgot-password-modal');
        this._wireDismissals('reset-password-modal');
        this._wireDismissals('change-password-modal');
        this._wireDismissals('delete-account-modal');

        this._wireForm('signup-form', this._handleSignupSubmit);
        this._wireForm('login-form', this._handleLoginSubmit);
        this._wireForm('forgot-password-form', this._handleForgotPasswordSubmit);
        this._wireForm('reset-password-form', this._handleResetPasswordSubmit);
        this._wireForm('change-password-form', this._handleChangePasswordSubmit);
        this._wireForm('delete-account-form', this._handleDeleteAccountSubmit);

        this._wireSwitchLink('signup-switch-to-login', 'signup-modal', this.showLogin);
        this._wireSwitchLink('login-switch-to-signup', 'login-modal', this.showSignup);
        this._wireSwitchLink('login-forgot-link', 'login-modal', this.showForgotPassword);

        // Live "passwords don't match" hint on every modal that takes a
        // new+confirm pair, so the user catches typos before submitting.
        this._wirePasswordMatchHint('signup-password',     'signup-password-confirm', 'signup-message');
        this._wirePasswordMatchHint('reset-password-new',  'reset-password-confirm',  'reset-password-message');
        this._wirePasswordMatchHint('change-password-new', 'change-password-confirm', 'change-password-message');
    },

    /**
     * Wire input listeners on a (new password, confirm password) pair so
     * the inline message reflects the match state as the user types. The
     * hint only fires once the confirm field has content — we don't
     * complain about a mismatch with an empty confirm box.
     */
    _wirePasswordMatchHint: function(passwordId, confirmId, messageElId) {
        const pwdEl = document.getElementById(passwordId);
        const confirmEl = document.getElementById(confirmId);
        const messageEl = document.getElementById(messageElId);
        if (!pwdEl || !confirmEl || !messageEl) return;

        const self = this;
        const update = function() {
            if (confirmEl.value.length > 0 && pwdEl.value !== confirmEl.value) {
                self._showMessage(messageEl, 'error', 'Passwords do not match.');
            } else {
                // Clear stale messages as the user edits; the submit
                // handler re-validates and re-surfaces anything that
                // still applies.
                self._clearMessage(messageElId);
            }
        };

        pwdEl.addEventListener('input', update);
        confirmEl.addEventListener('input', update);
    },

    showSignup: function() {
        this._reset('signup-form');
        this._clearMessage('signup-message');
        this.show('signup-modal');
        const email = document.getElementById('signup-email');
        if (email) { email.focus(); }
    },

    /**
     * @param {string} [prefillEmail] — when provided, the email field is
     *     pre-filled and focus moves to the password field. Used when the
     *     user is bounced from signup-of-existing-verified to login.
     */
    showLogin: function(prefillEmail) {
        this._reset('login-form');
        this._clearMessage('login-message');
        this.show('login-modal');
        const emailEl = document.getElementById('login-email');
        const passwordEl = document.getElementById('login-password');
        if (prefillEmail && emailEl) {
            emailEl.value = prefillEmail;
            if (passwordEl) { passwordEl.focus(); }
        } else if (emailEl) {
            emailEl.focus();
        }
    },

    /**
     * @param {string} [prefillEmail] — when provided, the email field is
     *     pre-filled. Used when bouncing from signup-of-existing-verified.
     */
    showForgotPassword: function(prefillEmail) {
        this._reset('forgot-password-form');
        this._clearMessage('forgot-password-message');
        this.show('forgot-password-modal');
        const emailEl = document.getElementById('forgot-password-email');
        if (emailEl) {
            if (prefillEmail) { emailEl.value = prefillEmail; }
            emailEl.focus();
        }
    },

    /**
     * Open the reset-password modal pre-loaded with a token from the email
     * link. Stashes the token on the modal so the submit handler can find it.
     * @param {string} token - reset token from /reset-password?token=...
     */
    showResetPassword: function(token) {
        const modal = document.getElementById('reset-password-modal');
        if (!modal) return;
        this._reset('reset-password-form');
        this._clearMessage('reset-password-message');
        modal.dataset.resetToken = token || '';
        this.show('reset-password-modal');
        const newPwd = document.getElementById('reset-password-new');
        if (newPwd) { newPwd.focus(); }
    },

    showChangePassword: function() {
        this._reset('change-password-form');
        this._clearMessage('change-password-message');
        this.show('change-password-modal');
        const current = document.getElementById('change-password-current');
        if (current) { current.focus(); }
    },

    showDeleteAccount: function() {
        this._reset('delete-account-form');
        this._clearMessage('delete-account-message');
        this.show('delete-account-modal');
        const password = document.getElementById('delete-account-password');
        if (password) { password.focus(); }
    },

    show: function(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) { modal.style.display = 'block'; }
    },

    hide: function(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) { modal.style.display = 'none'; }
    },

    _wireForm: function(formId, handler) {
        const form = document.getElementById(formId);
        if (form) {
            form.addEventListener('submit', handler.bind(this));
        }
    },

    _wireSwitchLink: function(linkId, fromModalId, switchTo) {
        const link = document.getElementById(linkId);
        if (!link) return;
        link.addEventListener('click', function(event) {
            event.preventDefault();
            this.hide(fromModalId);
            switchTo.call(this);
        }.bind(this));
    },

    _wireDismissals: function(modalId) {
        const modal = document.getElementById(modalId);
        if (!modal) return;

        modal.querySelectorAll('[data-close="' + modalId + '"]').forEach(function(btn) {
            btn.addEventListener('click', function() { modal.style.display = 'none'; });
        });
        const xClose = modal.querySelector('.modal-header .close');
        if (xClose && !xClose.hasAttribute('data-close')) {
            xClose.addEventListener('click', function() { modal.style.display = 'none'; });
        }
        modal.addEventListener('click', function(event) {
            if (event.target === modal) { modal.style.display = 'none'; }
        });
        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                modal.style.display = 'none';
            }
        });
    },

    _handleSignupSubmit: async function(event) {
        event.preventDefault();
        const email = document.getElementById('signup-email').value.trim();
        const password = document.getElementById('signup-password').value;
        const confirm = document.getElementById('signup-password-confirm').value;
        const messageEl = document.getElementById('signup-message');

        this._clearMessage('signup-message');

        if (password !== confirm) {
            this._showMessage(messageEl, 'error', 'Passwords do not match.');
            return;
        }
        if (password.length < 12) {
            this._showMessage(messageEl, 'error', 'Password must be at least 12 characters.');
            return;
        }

        const submitBtn = event.submitter || event.target.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;

        try {
            await window.TripWeather.Services.Auth.signup(email, password);
            this.hide('signup-modal');
            this._showVerificationSent(email);
        } catch (err) {
            const code = err.body && err.body.code;
            if (code === 'INVALID_PASSWORD') {
                this._showMessage(messageEl, 'error', err.message || 'Password does not meet requirements.');
            } else if (code === 'EMAIL_ALREADY_REGISTERED') {
                this._showAlreadyRegistered(messageEl, email);
            } else {
                this._showMessage(messageEl, 'error', err.message || 'Sign up failed. Please try again.');
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    /**
     * Inline error shown when signup hits an already-verified account.
     * Offers two next steps without leaving the signup modal — clicking
     * either link closes signup and opens the relevant modal pre-filled
     * with the email so the user doesn't have to retype it.
     */
    _showAlreadyRegistered: function(messageEl, email) {
        const safeEmail = window.TripWeather.Utils.Helpers.escapeHtml(email);
        const html =
            '<p>An account with <strong>' + safeEmail + '</strong> already exists. ' +
            'You can ' +
            '<a href="#" data-action="login">log in</a> ' +
            'or, if you\'ve forgotten your password, ' +
            '<a href="#" data-action="forgot">reset it via email</a>.</p>';
        this._showMessage(messageEl, 'error', html);

        const loginLink = messageEl.querySelector('a[data-action="login"]');
        if (loginLink) {
            loginLink.addEventListener('click', function(event) {
                event.preventDefault();
                this.hide('signup-modal');
                this.showLogin(email);
            }.bind(this));
        }
        const forgotLink = messageEl.querySelector('a[data-action="forgot"]');
        if (forgotLink) {
            forgotLink.addEventListener('click', function(event) {
                event.preventDefault();
                this.hide('signup-modal');
                this.showForgotPassword(email);
            }.bind(this));
        }
    },

    _handleLoginSubmit: async function(event) {
        event.preventDefault();
        const email = document.getElementById('login-email').value.trim();
        const password = document.getElementById('login-password').value;
        const rememberMeEl = document.getElementById('login-remember-me');
        const rememberMe = rememberMeEl ? rememberMeEl.checked : false;
        const messageEl = document.getElementById('login-message');

        this._clearMessage('login-message');

        const submitBtn = event.submitter || event.target.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;

        try {
            const result = await window.TripWeather.Services.Auth.login(email, password, rememberMe);
            this.hide('login-modal');
            const who = (result && result.user && result.user.email) || email;
            window.TripWeather.Managers.UI.showToast(who + ' logged in.', 'success');
        } catch (err) {
            const code = err.body && err.body.code;
            if (code === 'EMAIL_NOT_VERIFIED') {
                this._showMessage(messageEl, 'error',
                    'Your email address is not yet verified. ' +
                    '<a href="#" id="login-resend-link">Resend verification email</a>.');
                const link = document.getElementById('login-resend-link');
                if (link) {
                    link.addEventListener('click', async function(linkEvent) {
                        linkEvent.preventDefault();
                        try {
                            await window.TripWeather.Services.Auth.resendVerification(email);
                            this._showMessage(messageEl, 'info',
                                'If that address can receive a verification email, one has been sent.');
                        } catch (resendErr) {
                            this._showMessage(messageEl, 'error',
                                resendErr.message || 'Could not resend verification email.');
                        }
                    }.bind(this));
                }
            } else {
                this._showMessage(messageEl, 'error',
                    err.message || 'Login failed. Please try again.');
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    _handleForgotPasswordSubmit: async function(event) {
        event.preventDefault();
        const email = document.getElementById('forgot-password-email').value.trim();
        const messageEl = document.getElementById('forgot-password-message');
        this._clearMessage('forgot-password-message');

        const submitBtn = event.submitter || event.target.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;

        try {
            await window.TripWeather.Services.Auth.forgotPassword(email);
            this.hide('forgot-password-modal');
            window.TripWeather.Managers.UI.showInfoModal('Check your email',
                '<p>If <strong>' + window.TripWeather.Utils.Helpers.escapeHtml(email) + '</strong> is registered, ' +
                'we\'ve sent a password-reset link. The link expires in 24 hours.</p>' +
                '<p>If it doesn\'t arrive, check spam first.</p>');
        } catch (err) {
            this._showMessage(messageEl, 'error',
                err.message || 'Could not request a reset link. Please try again.');
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    _handleResetPasswordSubmit: async function(event) {
        event.preventDefault();
        const modal = document.getElementById('reset-password-modal');
        const token = modal && modal.dataset ? modal.dataset.resetToken : '';
        const newPwd = document.getElementById('reset-password-new').value;
        const confirm = document.getElementById('reset-password-confirm').value;
        const messageEl = document.getElementById('reset-password-message');

        this._clearMessage('reset-password-message');

        if (!token) {
            this._showMessage(messageEl, 'error',
                'This reset link is invalid. Please request a fresh one from the login screen.');
            return;
        }
        if (newPwd !== confirm) {
            this._showMessage(messageEl, 'error', 'Passwords do not match.');
            return;
        }
        if (newPwd.length < 12) {
            this._showMessage(messageEl, 'error', 'Password must be at least 12 characters.');
            return;
        }

        const submitBtn = event.submitter || event.target.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;

        try {
            await window.TripWeather.Services.Auth.resetPassword(token, newPwd);
            this.hide('reset-password-modal');
            window.TripWeather.Managers.UI.showToast(
                'Password updated. Please log in with your new password.', 'success');
            this.showLogin();
        } catch (err) {
            const code = err.body && err.body.code;
            if (code === 'INVALID_TOKEN') {
                this._showMessage(messageEl, 'error',
                    'This reset link is invalid or expired. Request a fresh one from the login screen.');
            } else if (code === 'INVALID_PASSWORD') {
                this._showMessage(messageEl, 'error',
                    err.message || 'Password does not meet requirements.');
            } else {
                this._showMessage(messageEl, 'error',
                    err.message || 'Could not reset password. Please try again.');
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    _handleChangePasswordSubmit: async function(event) {
        event.preventDefault();
        const current = document.getElementById('change-password-current').value;
        const newPwd = document.getElementById('change-password-new').value;
        const confirm = document.getElementById('change-password-confirm').value;
        const messageEl = document.getElementById('change-password-message');

        this._clearMessage('change-password-message');

        if (newPwd !== confirm) {
            this._showMessage(messageEl, 'error', 'New passwords do not match.');
            return;
        }
        if (newPwd.length < 12) {
            this._showMessage(messageEl, 'error', 'Password must be at least 12 characters.');
            return;
        }

        const submitBtn = event.submitter || event.target.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;

        try {
            await window.TripWeather.Services.Auth.changePassword(current, newPwd);
            this.hide('change-password-modal');
            window.TripWeather.Managers.UI.showToast(
                'Password updated. Other browsers will need to log in again.', 'success');
        } catch (err) {
            const code = err.body && err.body.code;
            if (code === 'INVALID_CREDENTIALS') {
                this._showMessage(messageEl, 'error', 'Current password is incorrect.');
            } else if (code === 'INVALID_PASSWORD') {
                this._showMessage(messageEl, 'error',
                    err.message || 'New password does not meet requirements.');
            } else {
                this._showMessage(messageEl, 'error',
                    err.message || 'Could not change password. Please try again.');
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    _handleDeleteAccountSubmit: async function(event) {
        event.preventDefault();
        const password = document.getElementById('delete-account-password').value;
        const ackEl = document.getElementById('delete-account-acknowledge');
        const acknowledged = ackEl ? ackEl.checked : false;
        const messageEl = document.getElementById('delete-account-message');

        this._clearMessage('delete-account-message');

        // The modal itself is the confirmation step: password + the explicit
        // acknowledgement checkbox + the destructive-red Delete button. No
        // additional confirm dialog — stacking a second modal on top of this
        // one created an invisible-prompt UX where clicking Delete appeared
        // to do nothing because the styled-confirm rendered behind this form.
        if (!password) {
            this._showMessage(messageEl, 'error',
                'Please enter your password to confirm.');
            return;
        }
        if (!acknowledged) {
            this._showMessage(messageEl, 'error',
                'Please tick the acknowledgement box to confirm.');
            return;
        }

        const submitBtn = event.submitter || event.target.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;

        try {
            await window.TripWeather.Services.Auth.deleteAccount(password);
            this.hide('delete-account-modal');
            window.TripWeather.Managers.UI.showToast(
                'Your account has been deleted.', 'info');
            // Full reload wipes any in-memory route / waypoint state from
            // the SPA and lands the user back on the anonymous home page.
            setTimeout(function() { window.location.reload(); }, 600);
        } catch (err) {
            const code = err.body && err.body.code;
            if (code === 'INVALID_CREDENTIALS') {
                this._showMessage(messageEl, 'error', 'Password is incorrect.');
            } else {
                this._showMessage(messageEl, 'error',
                    err.message || 'Could not delete account. Please try again.');
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    },

    _showVerificationSent: function(email) {
        const safeEmail = window.TripWeather.Utils.Helpers.escapeHtml(email);
        const html =
            '<p>Thanks for signing up. We\'ve sent a verification link to <strong>' + safeEmail + '</strong>.</p>' +
            '<p>Click the link in the email (it expires in 24 hours) to finish setting up your account.</p>' +
            '<p>If it doesn\'t arrive, check spam first, then ' +
            '<a href="#" id="verification-resend-link">resend the verification email</a>.</p>';
        window.TripWeather.Managers.UI.showInfoModal('Check your email', html);
        const link = document.getElementById('verification-resend-link');
        if (link) {
            link.addEventListener('click', async function(event) {
                event.preventDefault();
                try {
                    await window.TripWeather.Services.Auth.resendVerification(email);
                    window.TripWeather.Managers.UI.showToast(
                        'Verification email resent.', 'success');
                } catch (err) {
                    window.TripWeather.Managers.UI.showToast(
                        err.message || 'Could not resend verification email.', 'error');
                }
            });
        }
    },

    _showMessage: function(el, kind, html) {
        if (!el) return;
        el.className = 'auth-message ' + kind;
        el.innerHTML = html;
        el.hidden = false;
    },

    _clearMessage: function(elId) {
        const el = document.getElementById(elId);
        if (!el) return;
        el.innerHTML = '';
        el.hidden = true;
        el.className = 'auth-message';
    },

    _reset: function(formId) {
        const form = document.getElementById(formId);
        if (form) { form.reset(); }
    }
};
