/**
 * Trip Weather Application
 * Main application coordinator and initialization
 */

window.TripWeather = window.TripWeather || {};

window.TripWeather.App = {
    
    /**
     * Application configuration
     */
    config: {
        name: 'Trip Weather',
        version: '1.0.0',
        debug: true
    },

    /**
     * Current route information for tracking loaded routes
     */
    currentRoute: {
        id: null,
        name: null,
        userId: null
    },

    routeNameModalCallback: null,
    routeNameModalDefaultConfirmText: 'Save Name',
    routeNameMinLength: 3,

    /**
     * Initialize application
     */
    initialize: function() {
        console.log(`Initializing ${this.config.name} v${this.config.version}`);
        
        // Wait for DOM to be ready
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', this.onDOMReady.bind(this));
        } else {
            this.onDOMReady();
        }
    },

    /**
     * Handle DOM ready event
     */
    onDOMReady: function() {
        console.log('DOM ready, initializing application components...');
        
        try {
            // Initialize utility functions
            this.initializeUtilities();
            
            // Initialize services
            this.initializeServices();
            
            // Initialize managers in dependency order
            this.initializeManagers();
            
            // Initialize route header controls
            this.initializeRouteControls();
            
            // Initialize map
            this.initializeMap();
            
            // Setup global event listeners
            this.setupGlobalEvents();
            
            console.log('Application initialized successfully');
            
        } catch (error) {
            console.error('Failed to initialize application:', error);
            this.handleInitializationError(error);
        }
    },

    /**
     * Initialize utility functions (no dependencies)
     */
    initializeUtilities: function() {
        console.log('Initializing utilities...');
        
        // Utilities are self-contained in their respective files
        // They attach to window.TripWeather.Utils namespace automatically
        
        // Verify utilities are available
        const requiredUtils = ['Duration', 'Timezone', 'IconLoader', 'Helpers'];
        requiredUtils.forEach(function(utilName) {
            if (!window.TripWeather.Utils[utilName]) {
                throw new Error(`Required utility ${utilName} not found`);
            }
        });
        
        console.log('Utilities initialized');
    },

    /**
     * Initialize services (depend on utilities)
     */
    initializeServices: function() {
        console.log('Initializing services...');
        
        // Services are self-contained in their respective files
        // They attach to window.TripWeather.Services namespace automatically
        
        // Verify services are available
        const requiredServices = ['Location', 'Weather', 'RoutePersistence', 'EVChargingStation'];
        requiredServices.forEach(function(serviceName) {
            if (!window.TripWeather.Services[serviceName]) {
                throw new Error(`Required service ${serviceName} not found`);
            }
        });
        
        console.log('Services initialized');
    },

    /**
     * Initialize managers (depend on utilities and services)
     */
    initializeManagers: function() {
        console.log('Initializing managers...');
        
        // Managers are self-contained in their respective files
        // They attach to window.TripWeather.Managers namespace automatically
        
        // Verify managers are available
        const requiredManagers = ['Map', 'Waypoint', 'WaypointRenderer', 'Search', 'UI', 'Route', 'Layer', 'EVChargingStation'];
        requiredManagers.forEach(function(managerName) {
            if (!window.TripWeather.Managers[managerName]) {
                throw new Error(`Required manager ${managerName} not found`);
            }
        });
        
        // Initialize each manager
        window.TripWeather.Managers.Waypoint.initialize();
        window.TripWeather.Managers.UI.initialize();
        window.TripWeather.Managers.Search.initialize();
        window.TripWeather.Managers.Route.initialize();
        window.TripWeather.Managers.Layer.initialize();
        window.TripWeather.Managers.EVChargingStation.initialize();
        
        console.log('Managers initialized');
    },

    /**
     * Initialize map
     */
    initializeMap: function() {
        console.log('Initializing map...');
        
        // Initialize map with user location or default location
        window.TripWeather.Managers.Map.initializeWithUserLocation();
        
        console.log('Map initialized');
    },

    /**
     * Initialize route header controls and naming modal
     */
    initializeRouteControls: function() {
        console.log('Initializing route controls...');

        this.initializeRouteNameModal();
        this.updateCurrentRouteDisplay();

        const renameRouteBtn = document.getElementById('rename-route-btn');
        if (renameRouteBtn) {
            renameRouteBtn.addEventListener('click', this.handleRenameRoute.bind(this));

            const iconContainer = renameRouteBtn.querySelector('.route-name-icon');
            if (iconContainer) {
                window.TripWeather.Utils.IconLoader.loadSvgIcon('icons/pencil.svg', iconContainer, 'route-edit-icon');
            }
        } else {
            console.warn('Rename route button not found');
        }

        const newRouteBtn = document.getElementById('new-route-btn');
        if (newRouteBtn) {
            newRouteBtn.addEventListener('click', this.handleNewRoute.bind(this));
        } else {
            console.warn('New route button not found');
        }
    },

    /**
     * Setup modal interactions for naming routes
     */
    initializeRouteNameModal: function() {
        const modal = document.getElementById('route-name-modal');
        const input = document.getElementById('route-name-input');
        const confirmBtn = document.getElementById('route-name-confirm-btn');
        const cancelBtn = document.getElementById('route-name-cancel-btn');
        const closeBtn = modal ? modal.querySelector('.close') : null;

        if (!modal || !input || !confirmBtn || !cancelBtn) {
            console.warn('Route name modal elements not found');
            return;
        }

        confirmBtn.addEventListener('click', this.handleRouteNameConfirm.bind(this));
        cancelBtn.addEventListener('click', this.handleRouteNameCancel.bind(this));

        if (closeBtn) {
            closeBtn.addEventListener('click', this.handleRouteNameCancel.bind(this));
        }

        modal.addEventListener('click', function(event) {
            if (event.target === modal) {
                this.handleRouteNameCancel();
            }
        }.bind(this));

        input.addEventListener('keydown', function(event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                this.handleRouteNameConfirm();
            }
        }.bind(this));
    },

    /**
     * Update UI with current route name
     */
    updateCurrentRouteDisplay: function() {
        const routeNameSpan = document.getElementById('current-route-name');
        if (!routeNameSpan) return;

        const displayName = this.currentRoute.name ? this.currentRoute.name : 'New';
        routeNameSpan.textContent = displayName;
        routeNameSpan.title = displayName;
    },

    /**
     * Set current route name and refresh display
     * @param {string} name - Route name
     */
    setCurrentRouteName: function(name) {
        const trimmed = name ? name.trim() : '';
        this.currentRoute.name = trimmed !== '' ? trimmed : null;
        this.updateCurrentRouteDisplay();
    },

    /**
     * Reset route state for a new route
     */
    handleNewRoute: function() {
        console.log('Starting a new route');

        this.routeNameModalCallback = null;
        this.closeRouteNameModal();

        const existingUserId = this.currentRoute.userId || null;
        this.currentRoute = { id: null, name: null, userId: existingUserId };
        window.TripWeather.Managers.Waypoint.clearAllWaypoints();
        window.TripWeather.Managers.Route.clearRoute();
        this.updateCurrentRouteDisplay();

        window.TripWeather.Managers.UI.showToast('Started a new route.', 'info');
    },

    /**
     * Handle rename route button click
     */
    handleRenameRoute: function() {
        this.openRouteNameModal({
            initialValue: this.currentRoute.name || '',
            confirmText: 'Save Name'
        });
    },

    /**
     * Open the route naming modal
     * @param {object} options - Modal options
     */
    openRouteNameModal: function(options) {
        options = options || {};

        const modal = document.getElementById('route-name-modal');
        const input = document.getElementById('route-name-input');
        const confirmBtn = document.getElementById('route-name-confirm-btn');
        const titleEl = modal ? modal.querySelector('.modal-header h3') : null;

        if (!modal || !input || !confirmBtn) {
            console.warn('Cannot open route name modal; elements missing');
            return;
        }

        if (titleEl) {
            titleEl.textContent = options.title || 'Name This Route';
        }

        confirmBtn.textContent = options.confirmText || this.routeNameModalDefaultConfirmText;
        this.routeNameModalCallback = typeof options.onConfirm === 'function' ? options.onConfirm : null;

        const initialValue = options.initialValue !== undefined ? options.initialValue : (this.currentRoute.name || '');
        input.value = initialValue;
        if (this.routeNameMinLength) {
            input.setAttribute('minlength', this.routeNameMinLength);
        }

        modal.style.display = 'block';

        requestAnimationFrame(function() {
            input.focus();
            input.setSelectionRange(0, input.value.length);
        });
    },

    /**
     * Close the route naming modal
     */
    closeRouteNameModal: function() {
        const modal = document.getElementById('route-name-modal');
        const confirmBtn = document.getElementById('route-name-confirm-btn');
        const input = document.getElementById('route-name-input');

        if (modal) {
            modal.style.display = 'none';
        }

        if (confirmBtn) {
            confirmBtn.textContent = this.routeNameModalDefaultConfirmText;
        }

        if (input) {
            input.value = this.currentRoute.name || '';
        }
    },

    /**
     * Handle confirming a new route name
     */
    handleRouteNameConfirm: function() {
        const input = document.getElementById('route-name-input');
        if (!input) return;

        const value = input.value.trim();
        const minLength = this.routeNameMinLength || 1;
        if (value.length < minLength) {
            const message = minLength > 1
                ? `Route name must be at least ${minLength} characters.`
                : 'Please enter a route name.';
            window.TripWeather.Managers.UI.showToast(message, 'warning');
            input.focus();
            return;
        }

        const callback = this.routeNameModalCallback;
        const triggeredFromCallback = typeof callback === 'function';
        this.routeNameModalCallback = null;

        this.setCurrentRouteName(value);
        this.closeRouteNameModal();

        if (!triggeredFromCallback) {
            this.currentRoute.id = null;
            if (window.TripWeather.Managers.Waypoint && typeof window.TripWeather.Managers.Waypoint.clearWaypointIds === 'function') {
                window.TripWeather.Managers.Waypoint.clearWaypointIds();
            }
            window.TripWeather.Managers.UI.showToast(`Route name set to "${value}".`, 'success');
            console.log('Route renamed; treating as new route with cleared ID');
        }

        if (triggeredFromCallback) {
            callback(value);
        }
    },

    /**
     * Handle cancelling the route name modal
     */
    handleRouteNameCancel: function() {
        this.routeNameModalCallback = null;
        this.closeRouteNameModal();
    },

    /**
     * Setup global event listeners
     */
    setupGlobalEvents: function() {
        console.log('Setting up global events...');
        
        // Handle window resize
        window.addEventListener('resize', this.handleWindowResize.bind(this));
        
        // Handle online/offline status
        window.addEventListener('online', this.handleOnlineStatusChange.bind(this));
        window.addEventListener('offline', this.handleOnlineStatusChange.bind(this));
        
        // Handle beforeunload for cleanup
        window.addEventListener('beforeunload', this.handleBeforeUnload.bind(this));
        
        // Handle errors
        window.addEventListener('error', this.handleGlobalError.bind(this));
        window.addEventListener('unhandledrejection', this.handleUnhandledRejection.bind(this));
        
        // Setup route persistence buttons
        this.setupRoutePersistenceButtons();
        
        // Check for routeId in URL parameters
        this.checkForRouteIdInUrl();
        
        console.log('Global events setup complete');
    },

    /**
     * Setup route persistence button event listeners
     */
    setupRoutePersistenceButtons: function() {
        console.log('Setting up route persistence buttons...');
        
        const saveRouteBtn = document.getElementById('save-route-btn');
        const loadRouteBtn = document.getElementById('load-route-btn');
        const shareRouteBtn = document.getElementById('share-route-btn');
        
        if (saveRouteBtn) {
            saveRouteBtn.addEventListener('click', this.handleSaveRoute.bind(this));
        } else {
            console.warn('Save route button not found');
        }
        
        if (loadRouteBtn) {
            loadRouteBtn.addEventListener('click', this.handleLoadRoute.bind(this));
        } else {
            console.warn('Load route button not found');
        }
        
        if (shareRouteBtn) {
            shareRouteBtn.addEventListener('click', this.handleShareRoute.bind(this));
        } else {
            console.warn('Share route button not found');
        }
        
        console.log('Route persistence buttons setup complete');
    },

    /**
     * Handle save route button click
     */
    handleSaveRoute: function(skipNamePrompt) {
        console.log('Save route button clicked');

        if (skipNamePrompt && typeof skipNamePrompt.preventDefault === 'function') {
            skipNamePrompt.preventDefault();
            skipNamePrompt = false;
        }

        const shouldSkipNamePrompt = skipNamePrompt === true;
        
        try {
            const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
            
            if (waypoints.length === 0) {
                window.TripWeather.Managers.UI.showToast('No waypoints to save. Please add waypoints to your route first.', 'warning');
                return;
            }
            const routeId = this.currentRoute.id;
            const userId = this.currentRoute.userId;

            const routeNameForPrompt = this.currentRoute.name ? this.currentRoute.name.trim() : '';
            const minLength = this.routeNameMinLength || 1;

            if (!shouldSkipNamePrompt && routeNameForPrompt.length < minLength) {
                this.openRouteNameModal({
                    initialValue: routeNameForPrompt,
                    confirmText: 'Continue',
                    onConfirm: () => {
                        this.handleSaveRoute(true);
                    }
                });
                return;
            }

            const routeName = this.currentRoute.name ? this.currentRoute.name.trim() : '';

            if (routeName.length < minLength) {
                window.TripWeather.Managers.UI.showToast(`Route name must be at least ${minLength} characters.`, 'warning');
                this.openRouteNameModal({
                    initialValue: routeName,
                    confirmText: 'Continue',
                    onConfirm: () => {
                        this.handleSaveRoute(true);
                    }
                });
                return;
            }
            
            // Convert waypoints to DTO format
            const waypointDtos = window.TripWeather.Services.RoutePersistence.convertWaypointsToDto(waypoints);
            
            // Prepare route data
            const routeData = {
                id: routeId, // Include ID for updates, null for new routes
                name: routeName.trim(),
                waypoints: waypointDtos,
                userId: userId // Use current user ID or null for guest
            };
            
            // Show loading indicator
            window.TripWeather.Managers.UI.showLoading('persistence-loading-overlay');
            
            // Save route
            window.TripWeather.Services.RoutePersistence.saveRoute(routeData)
                .then(response => {
                    window.TripWeather.Managers.UI.hideLoading('persistence-loading-overlay');
                    
                    // Check if response is successful (2xx status codes)
                    if (response.ok) {
                        // Update current route tracking
                        this.currentRoute.id = response.data.id;
                        this.currentRoute.userId = response.data.userId;
                        this.setCurrentRouteName(response.data.name);
                        
                        // Show appropriate success message based on response status
                        let successMessage;
                        if (response.status === 201) {
                            successMessage = `The route "${response.data.name}" was successfully created.`;
                        } else if (response.status === 200) {
                            successMessage = `The route "${response.data.name}" was successfully updated.`;
                        } else {
                            successMessage = `The route "${response.data.name}" was saved successfully.`;
                        }
                        
                        window.TripWeather.Managers.UI.showToast(successMessage, 'success');
                        console.log('Route saved successfully:', response.data);
                    } else {
                        // Handle non-2xx response codes as errors
                        let errorMessage;
                        if (response.data && response.data.error) {
                            errorMessage = `Failed to save route: ${response.data.error}`;
                        } else {
                            errorMessage = `Failed to save route: Server returned status ${response.status}`;
                        }
                        
                        window.TripWeather.Managers.UI.showToast(errorMessage, 'error');
                        console.error('Error saving route:', response);
                    }
                })
                .catch(error => {
                    window.TripWeather.Managers.UI.hideLoading('persistence-loading-overlay');
                    window.TripWeather.Managers.UI.showToast(
                        `Failed to save route: ${error.message}`, 
                        'error'
                    );
                    console.error('Error saving route:', error);
                });
                
        } catch (error) {
            console.error('Error handling save route:', error);
            window.TripWeather.Managers.UI.showToast(
                `An error occurred while saving the route: ${error.message}`, 
                'error'
            );
        }
    },

    /**
     * Handle load route button click
     */
    handleLoadRoute: function() {
        console.log('Load route button clicked');
        
        try {
            // Show route search modal instead of prompting for ID
            window.TripWeather.Managers.Search.showRouteSearchModal();
        } catch (error) {
            console.error('Error handling load route:', error);
            window.TripWeather.Managers.UI.showToast(
                `An error occurred while loading the route: ${error.message}`,
                'error'
            );
        }
    },

    /**
     * Handle share route button click
     */
    handleShareRoute: function() {
        console.log('Share route button clicked');
        
        try {
            // Check if we have a route to share
            if (!this.currentRoute.id) {
                window.TripWeather.Managers.UI.showToast(
                    'Please save the route before sharing it.',
                    'warning'
                );
                return;
            }
            
            // Generate shareable link
            const shareableLink = this.generateShareableLink(this.currentRoute.id);
            
            // Copy to clipboard
            navigator.clipboard.writeText(shareableLink)
                .then(() => {
                    window.TripWeather.Managers.UI.showToast(
                        'Route link copied to clipboard!',
                        'success'
                    );
                })
                .catch(err => {
                    console.error('Failed to copy link to clipboard:', err);
                    // Fallback: show the link in a dialog
                    this.showShareableLinkDialog(shareableLink);
                });
                
        } catch (error) {
            console.error('Error handling share route:', error);
            window.TripWeather.Managers.UI.showToast(
                `An error occurred while sharing the route: ${error.message}`,
                'error'
            );
        }
    },

    /**
     * Generate a shareable link for a route
     * @param {string} routeId - UUID of the route
     * @returns {string} - Shareable URL
     */
    generateShareableLink: function(routeId) {
        const baseUrl = window.location.origin + window.location.pathname;
        return `${baseUrl}?routeId=${encodeURIComponent(routeId)}`;
    },

    /**
     * Show shareable link in a dialog as fallback
     * @param {string} link - Shareable link
     */
    showShareableLinkDialog: function(link) {
        const message = `Share this link with others:\n\n${link}\n\n(Copy this link manually)`;
        
        // Create a simple modal for the link
        const modal = document.createElement('div');
        modal.className = 'modal';
        modal.style.display = 'block';
        modal.innerHTML = `
            <div class="modal-content">
                <div class="modal-header">
                    <h3>Share Route</h3>
                    <span class="close">&times;</span>
                </div>
                <div class="modal-body">
                    <p>Copy this link to share your route:</p>
                    <input type="text" value="${link}" readonly style="width: 100%; padding: 8px; margin: 10px 0;">
                    <button id="copy-link-btn" class="modal-btn primary">Copy Link</button>
                </div>
            </div>
        `;
        
        document.body.appendChild(modal);
        
        // Setup event listeners
        const closeBtn = modal.querySelector('.close');
        const copyBtn = modal.getElementById('copy-link-btn');
        const input = modal.querySelector('input');
        
        const closeModal = () => {
            document.body.removeChild(modal);
        };
        
        closeBtn.addEventListener('click', closeModal);
        modal.addEventListener('click', (e) => {
            if (e.target === modal) closeModal();
        });
        
        copyBtn.addEventListener('click', () => {
            input.select();
            document.execCommand('copy');
            window.TripWeather.Managers.UI.showToast('Link copied to clipboard!', 'success');
        });
    },

    /**
     * Check for routeId in URL parameters and load the route if found
     */
    checkForRouteIdInUrl: function() {
        const urlParams = new URLSearchParams(window.location.search);
        const routeId = urlParams.get('routeId');
        
        if (routeId) {
            console.log('Found routeId in URL:', routeId);
            this.loadRouteFromUrl(routeId);
        }
    },

    /**
     * Wait for the map to finish initializing before performing map-dependent actions
     * @param {number} timeoutMs - Maximum time to wait for the map (default 5000ms)
     * @returns {Promise<void>}
     */
    waitForMapReady: function(timeoutMs = 5000) {
        return new Promise((resolve, reject) => {
            const mapManager = window.TripWeather.Managers.Map;
            
            if (!mapManager) {
                reject(new Error('Map manager is not available'));
                return;
            }
            
            if (mapManager.getMap()) {
                resolve();
                return;
            }
            
            const startTime = Date.now();
            const pollInterval = 100;
            const intervalId = setInterval(() => {
                if (mapManager.getMap()) {
                    clearInterval(intervalId);
                    resolve();
                } else if (Date.now() - startTime >= timeoutMs) {
                    clearInterval(intervalId);
                    reject(new Error('Map failed to initialize in time'));
                }
            }, pollInterval);
        });
    },

    /**
     * Load a route from URL parameter by reusing the existing search flow
     * @param {string} routeId - UUID of the route to load
     */
    loadRouteFromUrl: async function(routeId) {
        console.log('Loading route from URL via SearchManager:', routeId);
        
        const searchManager = window.TripWeather.Managers.Search;
        if (!searchManager || typeof searchManager.selectRouteSearchResult !== 'function') {
            console.error('SearchManager.selectRouteSearchResult is not available');
            window.TripWeather.Managers.UI.showToast(
                'Unable to load the shared route right now. Please try again.',
                'error'
            );
            return;
        }
        
        try {
            await this.waitForMapReady();
        } catch (mapError) {
            console.error('Map was not ready in time for route loading:', mapError);
            window.TripWeather.Managers.UI.showToast(
                'The map is still loading. Please try refreshing the page.',
                'error'
            );
            return;
        }
        
        try {
            await searchManager.selectRouteSearchResult(routeId);
        } catch (error) {
            console.error('Error loading route via shared link:', error);
            // Leave error messaging to SearchManager but ensure URL is cleaned up
            const url = new URL(window.location);
            url.searchParams.delete('routeId');
            window.history.replaceState({}, '', url);
        }
    },

    /**
     * Handle window resize
     */
    handleWindowResize: function() {
        // Debounce resize handling
        if (this.resizeTimer) {
            clearTimeout(this.resizeTimer);
        }
        
        this.resizeTimer = setTimeout(function() {
            // Trigger map resize if needed
            const map = window.TripWeather.Managers.Map.getMap();
            if (map) {
                map.invalidateSize();
            }
        }, 250);
    },

    /**
     * Handle online status change
     * @param {Event} event - Online/offline event
     */
    handleOnlineStatusChange: function(event) {
        const isOnline = event.type === 'online';
        console.log(`Application ${isOnline ? 'online' : 'offline'}`);
        
        if (isOnline) {
            window.TripWeather.Managers.UI.showNotification('Connection restored', 3000, 'success');
        } else {
            window.TripWeather.Managers.UI.showNotification('Connection lost', 3000, 'warning');
        }
    },

    /**
     * Handle before unload event
     * @param {Event} event - Before unload event
     */
    handleBeforeUnload: function(event) {
        // Could add cleanup logic here if needed
        console.log('Application shutting down...');
    },

    /**
     * Handle global JavaScript errors
     * @param {ErrorEvent} event - Error event
     */
    handleGlobalError: function(event) {
        console.error('Global error:', event.error);
        
        if (this.config.debug) {
            window.TripWeather.Managers.UI.showToast(
                `An error occurred: ${event.error.message}\n\nCheck console for details.`,
                'error'
            );
        }
    },

    /**
     * Handle unhandled promise rejections
     * @param {PromiseRejectionEvent} event - Rejection event
     */
    handleUnhandledRejection: function(event) {
        console.error('Unhandled promise rejection:', event.reason);
        
        if (this.config.debug) {
            window.TripWeather.Managers.UI.showToast(
                `An unhandled error occurred: ${event.reason}\n\nCheck console for details.`,
                'error'
            );
        }
    },

    /**
     * Handle initialization errors
     * @param {Error} error - Initialization error
     */
    handleInitializationError: function(error) {
        console.error('Application initialization failed:', error);
        
        // Show user-friendly error message
        const errorContainer = document.createElement('div');
        errorContainer.className = 'initialization-error';
        errorContainer.innerHTML = `
            <h2>Application Failed to Load</h2>
            <p>Sorry, but the Trip Weather application could not be initialized.</p>
            <p><strong>Error:</strong> ${error.message}</p>
            <p>Please try refreshing the page or contact support if the problem persists.</p>
            <button onclick="window.location.reload()">Refresh Page</button>
        `;
        
        // Hide main content and show error
        const main = document.querySelector('main');
        if (main) {
            main.style.display = 'none';
        }
        
        document.body.appendChild(errorContainer);
    },

    /**
     * Get application version
     * @returns {string} - Application version
     */
    getVersion: function() {
        return this.config.version;
    },

    /**
     * Get application name
     * @returns {string} - Application name
     */
    getName: function() {
        return this.config.name;
    },

    /**
     * Check if debug mode is enabled
     * @returns {boolean} - Whether debug mode is enabled
     */
    isDebugMode: function() {
        return this.config.debug;
    },

    /**
     * Get application status information
     * @returns {object} - Application status
     */
    getStatus: function() {
        return {
            name: this.config.name,
            version: this.config.version,
            debug: this.config.debug,
            initialized: true,
            waypointCount: window.TripWeather.Managers.Waypoint.getWaypointCount(),
            routeActive: window.TripWeather.Managers.Route.isRouteActive(),
            online: navigator.onLine
        };
    }
};

// Auto-initialize the application when script loads
window.TripWeather.App.initialize();
