/**
 * UI Manager
 * Handles UI overlays, modals, and general UI interactions
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.UI = {
    
    /**
     * Initialize UI manager
     */
    initialize: function() {
        // Initialize any UI components that need setup
        this.initializeTooltips();
        this.initializeKeyboardShortcuts();
        this.initializeMobileMenu();
        this.initializeProfileMenu();
        this.initializeConfirmModal();
    },

    /**
     * Wire the profile-icon dropdown in the header. Menu items are rendered
     * dynamically based on the current AuthService state — anonymous shows
     * Sign up / Log in / About; authenticated shows Log out / About. Toggle
     * behaviour mirrors initializeMobileMenu — outside-tap and Escape dismiss.
     */
    initializeProfileMenu: function() {
        const toggle = document.getElementById('profile-menu-btn');
        const menu = document.getElementById('profile-menu');
        if (!toggle || !menu) return;

        const close = function() {
            menu.hidden = true;
            toggle.setAttribute('aria-expanded', 'false');
        };
        this._closeProfileMenu = close;

        toggle.addEventListener('click', function(event) {
            event.stopPropagation();
            const isOpen = !menu.hidden;
            menu.hidden = isOpen;
            toggle.setAttribute('aria-expanded', isOpen ? 'false' : 'true');
        });

        document.addEventListener('click', function(event) {
            if (!menu.hidden && !menu.contains(event.target) && event.target !== toggle) {
                close();
            }
        });

        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && !menu.hidden) {
                close();
            }
        });

        // Initial render against current auth state, then re-render whenever it changes.
        const auth = window.TripWeather.Services.Auth;
        this.renderProfileMenu(auth ? auth.getCurrentUser() : null);
        if (auth && typeof auth.onChange === 'function') {
            auth.onChange(function(user) { this.renderProfileMenu(user); }.bind(this));
        }

        this.initializeInfoModal();
    },

    /**
     * Render the profile-menu items and the username display based on auth state.
     * @param {object|null} user - current AuthService user or null when anonymous
     */
    renderProfileMenu: function(user) {
        const menu = document.getElementById('profile-menu');
        const usernameEl = document.getElementById('profile-username');
        if (!menu) return;

        const items = user
            ? [
                { action: 'changePwd',  label: 'Change password' },
                { action: 'logout',     label: 'Log out' },
                { action: 'deleteAcct', label: 'Delete account' },
                { action: 'about',      label: 'About' }
            ]
            : [
                { action: 'signup', label: 'Sign up' },
                { action: 'login',  label: 'Log in' },
                { action: 'about',  label: 'About' }
            ];

        menu.innerHTML = '';
        items.forEach(function(item) {
            const btn = document.createElement('button');
            btn.className = 'profile-menu-item';
            btn.dataset.action = item.action;
            btn.setAttribute('role', 'menuitem');
            btn.textContent = item.label;
            btn.addEventListener('click', function() {
                if (this._closeProfileMenu) this._closeProfileMenu();
                this.handleProfileMenuAction(item.action);
            }.bind(this));
            menu.appendChild(btn);
        }.bind(this));

        if (usernameEl) {
            usernameEl.textContent = this._displayNameFor(user);
        }
    },

    _displayNameFor: function(user) {
        if (!user) return 'guest';
        // Email is the canonical login identifier — show it in full so the
        // user can see exactly which account they're logged into.
        if (user.email) return user.email;
        return user.displayName || 'user';
    },

    /**
     * Wire the close affordances on the generic info modal — the (×)
     * button, backdrop click, and Escape. Modal content is set by
     * showInfoModal at call time.
     */
    initializeInfoModal: function() {
        const modal = document.getElementById('info-modal');
        if (!modal) return;

        const closeBtn = modal.querySelector('.close');
        if (closeBtn) {
            closeBtn.addEventListener('click', this.hideInfoModal.bind(this));
        }

        modal.addEventListener('click', function(event) {
            if (event.target === modal) {
                this.hideInfoModal();
            }
        }.bind(this));

        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                this.hideInfoModal();
            }
        }.bind(this));
    },

    /**
     * Dispatch a profile-menu selection to its handler. Signup / login go
     * through AuthModals; logout goes through AuthService directly. Phase 4
     * will add change-password and delete-account.
     * @param {string} action - data-action attribute on the clicked item
     */
    handleProfileMenuAction: function(action) {
        const modals = window.TripWeather.Managers.AuthModals;
        const auth = window.TripWeather.Services.Auth;
        switch (action) {
            case 'signup':
                if (modals) modals.showSignup();
                break;
            case 'login':
                if (modals) modals.showLogin();
                break;
            case 'logout':
                if (auth) {
                    // Capture the email before logout() clears the cached user
                    // so the toast can still name the account that just left.
                    const previous = auth.getCurrentUser();
                    const who = (previous && previous.email) || 'user';
                    auth.logout().then(function() {
                        // Wipe the in-memory route so the next save doesn't
                        // resolve back to the just-logged-out user's route.
                        // Also avoids the surprising "I'm anonymous now but
                        // their waypoints are still on screen" experience.
                        if (window.TripWeather.App
                                && typeof window.TripWeather.App.resetCurrentRoute === 'function') {
                            window.TripWeather.App.resetCurrentRoute();
                        }
                        window.TripWeather.Managers.UI.showToast(who + ' logged out.', 'info');
                    });
                }
                break;
            case 'changePwd':
                if (modals) modals.showChangePassword();
                break;
            case 'deleteAcct':
                if (modals) modals.showDeleteAccount();
                break;
            case 'about':
                this.showAboutDialog();
                break;
        }
    },

    /**
     * Build and show the About dialog. Version comes from /version.txt,
     * generated by the writeVersionFile / bumpVersion Gradle tasks. Falls
     * back to "(unavailable)" if the file is missing (e.g. fresh checkout
     * before any build) or the fetch fails.
     */
    showAboutDialog: function() {
        const buildHtml = function(version) {
            return '<p><strong>Trip Weather</strong></p>' +
                '<p>version ' + version + '</p>' +
                '<p><a href="https://pjr22.com" target="_blank" rel="noopener">https://pjr22.com</a></p>';
        };

        this.showInfoModal('About', buildHtml('…'));

        fetch('version.txt', { cache: 'no-store' })
            .then(function(response) {
                return response.ok ? response.text() : '(unavailable)';
            })
            .catch(function() {
                return '(unavailable)';
            })
            .then(function(text) {
                const bodyEl = document.getElementById('info-modal-body');
                if (bodyEl) {
                    bodyEl.innerHTML = buildHtml(text.trim() || '(unavailable)');
                }
            });
    },

    /**
     * Show the generic info modal with the given title and HTML body.
     * @param {string} title - modal header text
     * @param {string} bodyHtml - HTML for modal body (caller-controlled, not user input)
     */
    showInfoModal: function(title, bodyHtml) {
        const modal = document.getElementById('info-modal');
        const titleEl = document.getElementById('info-modal-title');
        const bodyEl = document.getElementById('info-modal-body');
        if (!modal || !titleEl || !bodyEl) return;

        titleEl.textContent = title;
        bodyEl.innerHTML = bodyHtml;
        modal.style.display = 'block';
    },

    /**
     * Hide the generic info modal.
     */
    hideInfoModal: function() {
        const modal = document.getElementById('info-modal');
        if (modal) {
            modal.style.display = 'none';
        }
    },

    /**
     * Wire the header overflow toggle (☰) — only visible at narrow viewports.
     * Toggles the .menu-open class on .header-buttons; closes when an item
     * inside the menu is tapped, when the user taps outside, or on Escape.
     */
    initializeMobileMenu: function() {
        const toggle = document.getElementById('header-menu-toggle');
        const container = document.getElementById('header-buttons');
        if (!toggle || !container) return;

        const close = function() {
            container.classList.remove('menu-open');
            toggle.setAttribute('aria-expanded', 'false');
        };

        toggle.addEventListener('click', function(event) {
            event.stopPropagation();
            const isOpen = container.classList.toggle('menu-open');
            toggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
        });

        // Close after tapping any menu item — items inside the dropdown
        // are .header-menu-item; the toggle itself is excluded above.
        container.querySelectorAll('.header-menu-item').forEach(function(item) {
            item.addEventListener('click', close);
        });

        // Tap outside dismisses the menu.
        document.addEventListener('click', function(event) {
            if (!container.contains(event.target)) {
                close();
            }
        });

        // Escape dismisses; existing handleEscapeKey doesn't know about this.
        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && container.classList.contains('menu-open')) {
                close();
            }
        });
    },

    /**
     * Show loading overlay
     * @param {string} overlayId - ID of the overlay element
     */
    showLoading: function(overlayId) {
        window.TripWeather.Utils.Helpers.showLoading(overlayId);
    },

    /**
     * Hide loading overlay
     * @param {string} overlayId - ID of the overlay element
     */
    hideLoading: function(overlayId) {
        window.TripWeather.Utils.Helpers.hideLoading(overlayId);
    },

    /**
     * Show toast notification with consistent styling
     * @param {string} message - Message to display
     * @param {string} type - Toast type ('info', 'success', 'warning', 'error')
     * @param {number} duration - Duration in milliseconds (optional)
     */
    showToast: function(message, type, duration) {
        const toastType = type || 'info';
        console.log(`[${toastType.toUpperCase()}] ${message}`);
        return window.TripWeather.Utils.Helpers.showToast(message, toastType, duration);
    },

    /**
     * Deprecated alert wrapper retained for backward compatibility
     */
    showAlert: function(message, type, duration) {
        return this.showToast(message, type, duration);
    },

    /**
     * Wire close affordances on the confirm modal — × button, Cancel button,
     * backdrop click, and Escape key. The OK button gets its handler each
     * time {@link showConfirm} runs, since the callback changes per call.
     */
    initializeConfirmModal: function() {
        const modal = document.getElementById('confirm-modal');
        if (!modal) return;

        const finish = function(outcome) {
            modal.style.display = 'none';
            const callbacks = this._confirmCallbacks;
            this._confirmCallbacks = null;
            if (!callbacks) return;
            const fn = outcome === 'confirm' ? callbacks.onConfirm : callbacks.onCancel;
            if (typeof fn === 'function') {
                try { fn(); } catch (e) { console.warn('confirm callback threw', e); }
            }
        }.bind(this);

        const xClose = modal.querySelector('.modal-header .close');
        if (xClose) {
            xClose.addEventListener('click', function() { finish('cancel'); });
        }
        const cancelBtn = document.getElementById('confirm-modal-cancel');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', function() { finish('cancel'); });
        }
        const okBtn = document.getElementById('confirm-modal-ok');
        if (okBtn) {
            okBtn.addEventListener('click', function() { finish('confirm'); });
        }

        modal.addEventListener('click', function(event) {
            if (event.target === modal) finish('cancel');
        });

        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                finish('cancel');
            }
        });
    },

    /**
     * Show a styled confirmation dialog. Drop-in replacement for the old
     * native {@code confirm()} wrapper.
     *
     * @param {string} message - Confirmation message (rendered via textContent — safe for user-supplied strings)
     * @param {Function} onConfirm - Callback when the user clicks OK
     * @param {Function} [onCancel] - Callback when the user dismisses (Cancel / × / backdrop / Escape)
     * @param {object} [options]
     * @param {string} [options.title='Confirm'] - Modal header text
     * @param {string} [options.confirmLabel='OK'] - OK button text
     * @param {string} [options.cancelLabel='Cancel'] - Cancel button text
     * @param {boolean} [options.danger=false] - Style the OK button as destructive (red); also focuses Cancel by default so Enter doesn't trigger the destructive action.
     */
    showConfirm: function(message, onConfirm, onCancel, options) {
        const modal = document.getElementById('confirm-modal');
        const messageEl = document.getElementById('confirm-modal-message');
        const titleEl = document.getElementById('confirm-modal-title');
        const okBtn = document.getElementById('confirm-modal-ok');
        const cancelBtn = document.getElementById('confirm-modal-cancel');

        // Fallback so the UI still works if the markup is missing.
        if (!modal || !messageEl || !okBtn || !cancelBtn || !titleEl) {
            if (window.confirm(message)) {
                if (onConfirm) onConfirm();
            } else {
                if (onCancel) onCancel();
            }
            return;
        }

        const opts = options || {};
        titleEl.textContent = opts.title || 'Confirm';
        messageEl.textContent = message;
        okBtn.textContent = opts.confirmLabel || 'OK';
        cancelBtn.textContent = opts.cancelLabel || 'Cancel';
        okBtn.className = 'modal-btn ' + (opts.danger ? 'danger' : 'primary');

        this._confirmCallbacks = { onConfirm: onConfirm, onCancel: onCancel };
        modal.style.display = 'block';

        // Focus the safe action by default for destructive prompts; otherwise
        // focus OK so Enter confirms.
        const toFocus = opts.danger ? cancelBtn : okBtn;
        setTimeout(function() {
            try { toFocus.focus(); } catch (_) { /* ignore */ }
        }, 0);
    },

    /**
     * Show temporary notification
     * @param {string} message - Notification message
     * @param {number} duration - Duration in milliseconds (default 3000)
     * @param {string} type - Notification type ('info', 'success', 'warning', 'error')
     */
    showNotification: function(message, duration, type) {
        duration = duration || 3000;
        type = type || 'info';
        
        // Create notification element
        const notification = document.createElement('div');
        notification.className = `notification notification-${type}`;
        notification.textContent = message;
        
        // Add to page
        document.body.appendChild(notification);
        
        // Animate in
        setTimeout(function() {
            notification.classList.add('notification-show');
        }, 10);
        
        // Remove after duration
        setTimeout(function() {
            notification.classList.remove('notification-show');
            setTimeout(function() {
                if (notification.parentNode) {
                    notification.parentNode.removeChild(notification);
                }
            }, 300);
        }, duration);
    },

    /**
     * Update button state based on waypoint count
     * @param {number} waypointCount - Current number of waypoints
     */
    updateRouteButtonState: function(waypointCount) {
        const calculateRouteBtn = document.getElementById('calculate-route-btn');
        if (calculateRouteBtn) {
            if (waypointCount < 2) {
                calculateRouteBtn.disabled = true;
                calculateRouteBtn.title = 'Add at least 2 waypoints to calculate route';
            } else {
                calculateRouteBtn.disabled = false;
                calculateRouteBtn.title = 'Calculate optimal route between waypoints';
            }
        }
    },

    /**
     * Update route button text
     * @param {string} text - New button text
     */
    updateRouteButtonText: function(text) {
        const calculateRouteBtn = document.getElementById('calculate-route-btn');
        if (calculateRouteBtn) {
            // innerHTML so the .btn-label-extra span around "Route" is preserved
            // and can be hidden on narrow viewports. Callers pass internal,
            // hardcoded strings — never user input — so this is safe.
            calculateRouteBtn.innerHTML = text;
        }
    },

    /**
     * Initialize tooltips for elements with title attributes
     */
    initializeTooltips: function() {
        // Simple tooltip implementation - could be enhanced with a library
        const elementsWithTooltips = document.querySelectorAll('[title]');
        
        elementsWithTooltips.forEach(function(element) {
            element.addEventListener('mouseenter', function() {
                this.showTooltip(element);
            }.bind(this));
            
            element.addEventListener('mouseleave', function() {
                this.hideTooltip(element);
            }.bind(this));
        }.bind(this));
    },

    /**
     * Show tooltip for element
     * @param {HTMLElement} element - Element with tooltip
     */
    showTooltip: function(element) {
        const title = element.getAttribute('title');
        if (!title) return;
        
        // Store original title and remove attribute to prevent default tooltip
        element.setAttribute('data-original-title', title);
        element.removeAttribute('title');
        
        // Create tooltip element
        const tooltip = document.createElement('div');
        tooltip.className = 'tooltip';
        tooltip.textContent = title;
        tooltip.id = 'tooltip-' + Date.now();
        
        // Add to page first
        document.body.appendChild(tooltip);
        
        // Position tooltip after it's in the DOM
        const rect = element.getBoundingClientRect();
        const tooltipWidth = tooltip.offsetWidth;
        const tooltipHeight = tooltip.offsetHeight;
        
        // Calculate position to center tooltip above element
        tooltip.style.left = rect.left + (rect.width / 2) - (tooltipWidth / 2) + 'px';
        tooltip.style.top = rect.top - tooltipHeight - 5 + 'px';
        
        // Show tooltip
        setTimeout(function() {
            tooltip.classList.add('tooltip-show');
        }, 10);
        
        // Store tooltip reference
        element._tooltip = tooltip;
    },

    /**
     * Hide tooltip for element
     * @param {HTMLElement} element - Element with tooltip
     */
    hideTooltip: function(element) {
        const tooltip = element._tooltip;
        if (!tooltip) return;
        
        // Hide and remove tooltip
        tooltip.classList.remove('tooltip-show');
        setTimeout(function() {
            if (tooltip.parentNode) {
                tooltip.parentNode.removeChild(tooltip);
            }
        }, 200);
        
        // Restore original title
        const originalTitle = element.getAttribute('data-original-title');
        if (originalTitle) {
            element.setAttribute('title', originalTitle);
            element.removeAttribute('data-original-title');
        }
        
        // Clear reference
        element._tooltip = null;
    },

    /**
     * Initialize keyboard shortcuts
     */
    initializeKeyboardShortcuts: function() {
        document.addEventListener('keydown', function(event) {
            // Escape key - close modals, cancel operations
            if (event.key === 'Escape') {
                this.handleEscapeKey();
            }
            
            // Ctrl+Enter or Cmd+Enter - calculate route
            if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
                this.handleCalculateRouteShortcut();
            }
            
            // Ctrl+F or Cmd+F - focus search
            if ((event.ctrlKey || event.metaKey) && event.key === 'f') {
                event.preventDefault();
                this.handleSearchShortcut();
            }
        }.bind(this));
    },

    /**
     * Handle escape key press
     */
    handleEscapeKey: function() {
        const routeNameModal = document.getElementById('route-name-modal');
        if (routeNameModal && routeNameModal.style.display === 'block') {
            window.TripWeather.App.handleRouteNameCancel();
            return;
        }

        // Close search modal if open
        const searchModal = document.getElementById('search-modal');
        if (searchModal && searchModal.style.display === 'block') {
            window.TripWeather.Managers.Search.hideModal();
            return;
        }
        
        // Cancel waypoint replacement if active
        const replacingWaypointSequence = window.TripWeather.Managers.Waypoint.getReplacingWaypointSequence();
        if (replacingWaypointSequence !== null) {
            window.TripWeather.Managers.Waypoint.setReplacingWaypointSequence(null);
            window.TripWeather.Managers.Map.setCursor('');
            this.showNotification('Waypoint replacement cancelled', 2000, 'info');
            return;
        }
    },

    /**
     * Handle calculate route shortcut
     */
    handleCalculateRouteShortcut: function() {
        const calculateRouteBtn = document.getElementById('calculate-route-btn');
        if (calculateRouteBtn && !calculateRouteBtn.disabled) {
            calculateRouteBtn.click();
        }
    },

    /**
     * Handle search shortcut
     */
    handleSearchShortcut: function() {
        const searchButton = document.getElementById('search-location-btn');
        if (searchButton) {
            searchButton.click();
        } else if (window.TripWeather.Managers.Search) {
            window.TripWeather.Managers.Search.showModal();
        }
    },

    /**
     * Enable/disable UI elements during operations
     * @param {boolean} disabled - Whether to disable elements
     * @param {string} containerId - Container ID to limit scope (optional)
     */
    setElementsDisabled: function(disabled, containerId) {
        const container = containerId ? document.getElementById(containerId) : document;
        if (!container) return;
        
        const interactiveElements = container.querySelectorAll('button, input, select, textarea');
        interactiveElements.forEach(function(element) {
            element.disabled = disabled;
        });
    },

    /**
     * Add CSS class to element
     * @param {string} elementId - Element ID
     * @param {string} className - CSS class to add
     */
    addClass: function(elementId, className) {
        const element = document.getElementById(elementId);
        if (element) {
            element.classList.add(className);
        }
    },

    /**
     * Remove CSS class from element
     * @param {string} elementId - Element ID
     * @param {string} className - CSS class to remove
     */
    removeClass: function(elementId, className) {
        const element = document.getElementById(elementId);
        if (element) {
            element.classList.remove(className);
        }
    },

    /**
     * Toggle CSS class on element
     * @param {string} elementId - Element ID
     * @param {string} className - CSS class to toggle
     */
    toggleClass: function(elementId, className) {
        const element = document.getElementById(elementId);
        if (element) {
            element.classList.toggle(className);
        }
    },

    /**
     * Check if element has CSS class
     * @param {string} elementId - Element ID
     * @param {string} className - CSS class to check
     * @returns {boolean} - Whether element has class
     */
    hasClass: function(elementId, className) {
        const element = document.getElementById(elementId);
        return element ? element.classList.contains(className) : false;
    },

    /**
     * Get element by ID with null check
     * @param {string} elementId - Element ID
     * @returns {HTMLElement|null} - Element or null
     */
    getElement: function(elementId) {
        return document.getElementById(elementId) || null;
    },

    /**
     * Check if element exists
     * @param {string} elementId - Element ID
     * @returns {boolean} - Whether element exists
     */
    elementExists: function(elementId) {
        return document.getElementById(elementId) !== null;
    }
};
