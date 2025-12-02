/**
 * EV Charging Station Manager
 * Handles EV charging station display, visibility, and user interactions
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.EVChargingStation = {
    
    // State
    stationMarkers: [],
    currentStations: [],
    stationsVisible: false,
    isLoading: false,
    
    /**
     * Initialize EV Charging Station Manager
     */
    initialize: function() {
        console.log('Initializing EV Charging Station Manager...');
        this.setupMapControl();
        // Initialize button state after a short delay to ensure DOM is ready
        setTimeout(() => {
            this.updateButtonState();
        }, 500);
        console.log('EV Charging Station Manager initialized');
    },
    
    /**
     * Setup the EV charging search control on the map
     */
    setupMapControl: function() {
        const checkMapInterval = setInterval(() => {
            const map = window.TripWeather.Managers.Map.getMap();
            if (map) {
                clearInterval(checkMapInterval);
                this.addEVChargingControl(map);
                console.log('EV Charging Station Manager: Map control added');
            }
        }, 500);
    },
    
    /**
     * Add EV charging search control to the map
     * @param {L.Map} map - Leaflet map instance
     */
    addEVChargingControl: function(map) {
        const self = this;
        
        const EVChargingControl = L.Control.extend({
            options: {
                position: 'topleft'
            },
            
            onAdd: function(map) {
                const container = L.DomUtil.create('div', 'leaflet-bar ev-charging-control');
                container.title = 'Search for EV charging stations along the route';
                container.id = 'ev-charging-btn';
                
                // Load the lightning bolt icon
                window.TripWeather.Utils.IconLoader.loadSvgIcon('icons/ev-charging.svg', container, 'ev-charging-icon');
                
                // Fallback if icon loader fails
                if (container.innerHTML === '') {
                    container.innerHTML = '<span>⚡</span>';
                }
                
                // Start disabled (no route active)
                container.classList.add('disabled');
                
                L.DomEvent.on(container, 'click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    L.DomEvent.preventDefault(e);
                    
                    if (!container.classList.contains('disabled')) {
                        self.handleSearchClick();
                    }
                });
                
                return container;
            }
        });
        
        map.addControl(new EVChargingControl());
    },
    
    /**
     * Handle click on the EV charging search button
     */
    handleSearchClick: function() {
        if (this.isLoading) {
            return;
        }
        
        // If stations are already visible, toggle them off
        if (this.stationsVisible && this.stationMarkers.length > 0) {
            this.clearStations();
            window.TripWeather.Managers.UI.showToast('EV charging stations hidden', 'info');
            return;
        }
        
        // Otherwise, fetch new stations
        this.fetchStations();
    },
    
    /**
     * Fetch EV charging stations along the current route
     */
    fetchStations: function() {
        const routeManager = window.TripWeather.Managers.Route;
        const currentRoute = routeManager.getCurrentRoute();
        
        if (!currentRoute) {
            window.TripWeather.Managers.UI.showToast('Please calculate a route first', 'warning');
            return;
        }
        
        this.isLoading = true;
        this.updateButtonState();
        window.TripWeather.Managers.UI.showToast('Searching for EV charging stations...', 'info');
        
        window.TripWeather.Services.EVChargingStation.getStationsAlongRoute(currentRoute)
            .then(function(stations) {
                this.isLoading = false;
                
                if (stations.length === 0) {
                    window.TripWeather.Managers.UI.showToast('No EV charging stations found along this route', 'info');
                    this.updateButtonState();
                    return;
                }
                
                this.displayStations(stations);
                window.TripWeather.Managers.UI.showToast(`Found ${stations.length} EV charging station(s)`, 'success');
            }.bind(this))
            .catch(function(error) {
                this.isLoading = false;
                this.updateButtonState();
                console.error('Error fetching EV charging stations:', error);
                window.TripWeather.Managers.UI.showToast('Failed to fetch EV charging stations: ' + error.message, 'error');
            }.bind(this));
    },
    
    /**
     * Display stations on the map
     * @param {Array} stations - Array of station GeoJSON features
     */
    displayStations: function(stations) {
        this.clearStations();
        
        const map = window.TripWeather.Managers.Map.getMap();
        if (!map) return;
        
        const evService = window.TripWeather.Services.EVChargingStation;
        
        stations.forEach(function(station) {
            const formattedStation = evService.formatStationData(station);
            this.currentStations.push(formattedStation);
            
            const marker = this.createStationMarker(formattedStation, map);
            this.stationMarkers.push(marker);
        }.bind(this));
        
        this.stationsVisible = true;
        this.updateButtonState();
    },
    
    /**
     * Create a marker for an EV charging station
     * @param {object} station - Formatted station data
     * @param {L.Map} map - Leaflet map instance
     * @returns {L.Marker} - Leaflet marker
     */
    createStationMarker: function(station, map) {
        const evIcon = L.divIcon({
            className: 'ev-station-marker',
            html: '<span class="ev-station-icon">⚡</span>',
            iconSize: [32, 32],
            iconAnchor: [16, 32],
            popupAnchor: [0, -32]
        });
        
        const marker = L.marker([station.latitude, station.longitude], {
            icon: evIcon
        }).addTo(map);
        
        // Store station data on marker for later use
        marker.stationData = station;
        
        // Create popup content
        const popupContent = window.TripWeather.Services.EVChargingStation.createStationPopupContent(station);
        marker.bindPopup(popupContent, {
            maxWidth: 300,
            className: 'ev-station-popup-container'
        });
        
        return marker;
    },
    
    /**
     * Clear all station markers from the map
     */
    clearStations: function() {
        const map = window.TripWeather.Managers.Map.getMap();
        
        if (map) {
            this.stationMarkers.forEach(function(marker) {
                map.removeLayer(marker);
            });
        }
        
        this.stationMarkers = [];
        this.currentStations = [];
        this.stationsVisible = false;
        this.updateButtonState();
    },
    
    /**
     * Add a station as a waypoint
     * @param {string} stationId - Station ID
     */
    addStationAsWaypoint: function(stationId) {
        const station = this.currentStations.find(function(s) {
            return s.id === stationId || s.id === parseInt(stationId);
        });
        
        if (!station) {
            window.TripWeather.Managers.UI.showToast('Station not found', 'error');
            return;
        }
        
        // Create location info object
        const locationInfo = {
            locationName: station.name,
            timezoneName: '',
            timezoneStdOffset: '',
            timezoneDstOffset: '',
            timezoneStdAbbr: '',
            timezoneDstAbbr: ''
        };
        
        // Add waypoint using WaypointManager
        if (window.TripWeather.Managers.Waypoint) {
            const waypoint = window.TripWeather.Managers.Waypoint.addWaypoint(
                station.latitude,
                station.longitude,
                0, // Elevation (not available from EV API)
                locationInfo,
                null, // No existing waypoint object
                false // Perform validation
            );
            
            if (waypoint) {
                // Close any open popups
                this.stationMarkers.forEach(function(marker) {
                    marker.closePopup();
                });
                
                // Open popup for the newly added waypoint
                if (window.TripWeather.Managers.WaypointRenderer) {
                    window.TripWeather.Managers.WaypointRenderer.openWaypointPopup(waypoint.sequence);
                }
                
                window.TripWeather.Managers.UI.showToast(`Added "${station.name}" to waypoints`, 'success');
            }
        } else {
            console.error('WaypointManager not available');
            window.TripWeather.Managers.UI.showToast('Unable to add waypoint. Please try again.', 'error');
        }
    },
    
    /**
     * Update button state based on route availability
     */
    updateButtonState: function() {
        const container = document.getElementById('ev-charging-btn');
        if (!container) {
            console.warn('EV Charging Station button not found in DOM');
            return;
        }
        
        const routeActive = window.TripWeather.Managers.Route.isRouteActive();
        console.log('EV Charging Station - updateButtonState:', {
            routeActive: routeActive,
            isLoading: this.isLoading,
            stationsVisible: this.stationsVisible,
            currentStationsCount: this.currentStations.length
        });
        
        if (this.isLoading) {
            container.classList.add('loading');
            container.classList.remove('disabled');
            container.classList.remove('active');
            container.title = 'Loading...';
        } else if (!routeActive) {
            container.classList.add('disabled');
            container.classList.remove('loading');
            container.classList.remove('active');
            container.title = 'Calculate a route first to search for EV charging stations';
        } else if (this.stationsVisible) {
            container.classList.remove('disabled');
            container.classList.remove('loading');
            container.classList.add('active');
            container.title = `${this.currentStations.length} EV charging station(s) shown - click to hide`;
        } else {
            container.classList.remove('disabled');
            container.classList.remove('loading');
            container.classList.remove('active');
            container.title = 'Search for EV charging stations along the route';
        }
    },
    
    /**
     * Called when a route is calculated
     * @param {object} routeData - Route data from calculation
     */
    onRouteCalculated: function(routeData) {
        // Delay the button state update to ensure the route is fully set as active
        setTimeout(() => {
            this.updateButtonState();
        }, 100);
    },
    
    /**
     * Called when route is cleared
     */
    onRouteCleared: function() {
        this.clearStations();
        this.updateButtonState();
    },
    
    /**
     * Get current stations count
     * @returns {number} - Number of displayed stations
     */
    getStationsCount: function() {
        return this.currentStations.length;
    },
    
    /**
     * Check if stations are currently visible
     * @returns {boolean} - Whether stations are visible
     */
    areStationsVisible: function() {
        return this.stationsVisible;
    }
};
