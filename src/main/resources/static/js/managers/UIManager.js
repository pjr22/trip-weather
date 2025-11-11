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
     * Show confirmation dialog
     * @param {string} message - Confirmation message
     * @param {Function} onConfirm - Callback when confirmed
     * @param {Function} onCancel - Callback when cancelled (optional)
     */
    showConfirm: function(message, onConfirm, onCancel) {
        if (confirm(message)) {
            if (onConfirm) onConfirm();
        } else {
            if (onCancel) onCancel();
        }
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
            calculateRouteBtn.textContent = text;
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
        
        // Add to page
        document.body.appendChild(tooltip);
        
        // Position tooltip
        const rect = element.getBoundingClientRect();
        tooltip.style.left = rect.left + (rect.width / 2) - (tooltip.offsetWidth / 2) + 'px';
        tooltip.style.top = rect.top - tooltip.offsetHeight - 5 + 'px';
        
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
