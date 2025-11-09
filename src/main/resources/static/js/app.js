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
     * Initialize the application
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
        const requiredServices = ['Location', 'Weather'];
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
        const requiredManagers = ['Map', 'Waypoint', 'WaypointRenderer', 'Search', 'UI', 'Route'];
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
        
        console.log('Global events setup complete');
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
            window.TripWeather.Managers.UI.showAlert(
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
            window.TripWeather.Managers.UI.showAlert(
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
