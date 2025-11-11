/**
 * Search Manager
 * Handles location search functionality and modal management
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Search = {
    
    // Search state
    searchDebounceTimer: null,
    routeSearchDebounceTimer: null,
    
    /**
     * Initialize search functionality
     */
    initialize: function() {
        const modal = document.getElementById('search-modal');
        const btn = document.getElementById('search-location-btn');
        const closeBtn = document.querySelector('.close');
        const searchInput = document.getElementById('search-input');
        
        if (btn) {
            btn.onclick = function() {
                this.showModal();
            }.bind(this);
        }
        
        if (closeBtn) {
            closeBtn.onclick = function() {
                this.hideModal();
            }.bind(this);
        }
        
        if (modal) {
            modal.onclick = function(event) {
                if (event.target == modal) {
                    this.hideModal();
                }
            }.bind(this);
        }
        
        if (searchInput) {
            searchInput.addEventListener('input', this.handleSearchInput.bind(this));
        }
        
        // Initialize route search modal
        this.initializeRouteSearch();
    },

    /**
     * Show search modal
     */
    showModal: function() {
        const modal = document.getElementById('search-modal');
        const searchInput = document.getElementById('search-input');
        
        if (modal) {
            modal.style.display = 'block';
        }
        
        if (searchInput) {
            searchInput.focus();
        }
    },

    /**
     * Hide search modal
     */
    hideModal: function() {
        const modal = document.getElementById('search-modal');
        const searchInput = document.getElementById('search-input');
        const searchResults = document.getElementById('search-results');
        
        if (modal) {
            modal.style.display = 'none';
        }
        
        if (searchInput) {
            searchInput.value = '';
        }
        
        if (searchResults) {
            searchResults.innerHTML = '';
        }
    },

    /**
     * Handle search input with debouncing
     * @param {Event} event - Input event
     */
    handleSearchInput: function(event) {
        const searchInput = event.target;
        const query = searchInput.value.trim();
        
        if (this.searchDebounceTimer) {
            clearTimeout(this.searchDebounceTimer);
        }
        
        if (query.length < 2) {
            document.getElementById('search-results').innerHTML = '';
            return;
        }
        
        document.getElementById('search-results').innerHTML = '<div class="search-loading">Searching...</div>';
        
        this.searchDebounceTimer = setTimeout(function() {
            this.performSearch(query);
        }.bind(this), 1000);
    },

    /**
     * Perform location search
     * @param {string} query - Search query
     */
    performSearch: function(query) {
        window.TripWeather.Services.Location.searchLocations(query)
            .then(function(data) {
                window.TripWeather.Managers.Search.displaySearchResults(data);
            })
            .catch(function(error) {
                console.error('Search error:', error);
                document.getElementById('search-results').innerHTML = '<div class="search-no-results">Error performing search</div>';
            });
    },

    /**
     * Display search results in modal
     * @param {object} data - Search response data
     */
    displaySearchResults: function(data) {
        const resultsContainer = document.getElementById('search-results');
        
        if (!data || !data.features || data.features.length === 0) {
            resultsContainer.innerHTML = '<div class="search-no-results">No results found</div>';
            return;
        }
        
        resultsContainer.innerHTML = '';
        
        data.features.forEach(feature => {
            const properties = feature.properties;
            const coordinates = feature.geometry.coordinates;
            
            const resultItem = document.createElement('div');
            resultItem.className = 'search-result-item';
            
            // Use the LocationService to get consistent location naming
            const locationInfo = window.TripWeather.Services.Location.extractLocationFromFeature(feature);
            const displayInfo = window.TripWeather.Services.Location.formatLocationDisplay(feature);
            const label = displayInfo.label;
            const details = displayInfo.details;
            
            resultItem.innerHTML = `
                <div class="result-label">${label}</div>
                <div class="result-details">${details}</div>
            `;
            
            // Pass the complete feature data to avoid redundant geocode calls
            resultItem.onclick = function() {
                window.TripWeather.Managers.Search.selectSearchResult(coordinates[1], coordinates[0], label, feature);
            };
            
            resultsContainer.appendChild(resultItem);
        });
    },

    /**
     * Handle selection of search result
     * @param {number} lat - Latitude
     * @param {number} lng - Longitude
     * @param {string} locationName - Location name
     * @param {object} feature - GeoJSON feature object
     */
    selectSearchResult: function(lat, lng, locationName, feature) {
        this.hideModal();
        
        // Check if we're replacing a waypoint
        const replacingWaypointSequence = window.TripWeather.Managers.Waypoint.getReplacingWaypointSequence();
        
        if (replacingWaypointSequence !== null) {
            this.replaceWaypointLocationFromSearch(replacingWaypointSequence, lat, lng, locationName, feature);
            window.TripWeather.Managers.Waypoint.setReplacingWaypointSequence(null);
        } else {
            this.addWaypointFromSearch(lat, lng, locationName, feature);
        }
        
        // Center map on selected location
        window.TripWeather.Managers.Map.centerOn(lat, lng, 13);
    },

    /**
     * Add waypoint from search result
     * @param {number} lat - Latitude
     * @param {number} lng - Longitude
     * @param {string} locationName - Location name
     * @param {object} feature - GeoJSON feature object
     */
    addWaypointFromSearch: function(lat, lng, locationName, feature) {
        // Extract location information from search result
        const locationInfo = window.TripWeather.Services.Location.extractLocationFromFeature(feature);
        
        // Create waypoint with pre-fetched location data
        const waypoint = window.TripWeather.Managers.Waypoint.addWaypoint(lat, lng, locationInfo);
        
        // No need to call fetchLocationName since we already have all the data
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.sequence === waypoint.sequence);
        if (index !== -1) {
            const marker = window.TripWeather.Managers.Waypoint.waypointMarkers[index];
            if (marker && window.TripWeather.Managers.WaypointRenderer) {
                window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
    },

    /**
     * Replace waypoint location from search result
     * @param {number} sequence - Sequence of waypoint to replace
     * @param {number} lat - New latitude
     * @param {number} lng - New longitude
     * @param {string} locationName - Location name
     * @param {object} feature - GeoJSON feature object
     */
    replaceWaypointLocationFromSearch: function(sequence, lat, lng, locationName, feature) {
        // Extract location information from search result
        const locationInfo = window.TripWeather.Services.Location.extractLocationFromFeature(feature);
        
        // Replace waypoint with pre-fetched location data
        window.TripWeather.Managers.Waypoint.replaceWaypointLocation(sequence, lat, lng, locationInfo);
    },

    /**
     * Search for new location for specific waypoint
     * @param {number} sequence - Waypoint sequence to search for
     */
    searchNewLocationForWaypoint: function(sequence) {
        window.TripWeather.Managers.Waypoint.setReplacingWaypointSequence(sequence);
        this.showModal();
    },

    /**
     * Clear search results
     */
    clearResults: function() {
        const searchResults = document.getElementById('search-results');
        if (searchResults) {
            searchResults.innerHTML = '';
        }
    },

    /**
     * Get current search query
     * @returns {string} - Current search query
     */
    getCurrentQuery: function() {
        const searchInput = document.getElementById('search-input');
        return searchInput ? searchInput.value.trim() : '';
    },

    /**
     * Set search query
     * @param {string} query - Search query to set
     */
    setQuery: function(query) {
        const searchInput = document.getElementById('search-input');
        if (searchInput) {
            searchInput.value = query;
        }
    },

    /**
     * Focus search input
     */
    focusSearch: function() {
        const searchInput = document.getElementById('search-input');
        if (searchInput) {
            searchInput.focus();
        }
    },
    
    /**
     * Initialize route search functionality
     */
    initializeRouteSearch: function() {
        const modal = document.getElementById('route-search-modal');
        const closeBtn = modal ? modal.querySelector('.close') : null;
        const routeSearchInput = document.getElementById('route-search-input');
        
        if (closeBtn) {
            closeBtn.onclick = function() {
                this.hideRouteSearchModal();
            }.bind(this);
        }
        
        if (modal) {
            modal.onclick = function(event) {
                if (event.target == modal) {
                    this.hideRouteSearchModal();
                }
            }.bind(this);
        }
        
        if (routeSearchInput) {
            routeSearchInput.addEventListener('input', this.handleRouteSearchInput.bind(this));
        }
    },
    
    /**
     * Show route search modal
     */
    showRouteSearchModal: function() {
        const modal = document.getElementById('route-search-modal');
        const routeSearchInput = document.getElementById('route-search-input');
        
        if (modal) {
            modal.style.display = 'block';
        }
        
        if (routeSearchInput) {
            routeSearchInput.focus();
        }
    },
    
    /**
     * Hide route search modal
     */
    hideRouteSearchModal: function() {
        const modal = document.getElementById('route-search-modal');
        const routeSearchInput = document.getElementById('route-search-input');
        const routeSearchResults = document.getElementById('route-search-results');
        
        if (modal) {
            modal.style.display = 'none';
        }
        
        if (routeSearchInput) {
            routeSearchInput.value = '';
        }
        
        if (routeSearchResults) {
            routeSearchResults.innerHTML = '';
        }
    },
    
    /**
     * Handle route search input with debouncing
     * @param {Event} event - Input event
     */
    handleRouteSearchInput: function(event) {
        const searchInput = event.target;
        const query = searchInput.value.trim();
        
        if (this.routeSearchDebounceTimer) {
            clearTimeout(this.routeSearchDebounceTimer);
        }
        
        if (query.length < 2) {
            document.getElementById('route-search-results').innerHTML = '';
            return;
        }
        
        document.getElementById('route-search-results').innerHTML = '<div class="search-loading">Searching routes...</div>';
        
        this.routeSearchDebounceTimer = setTimeout(function() {
            this.performRouteSearch(query);
        }.bind(this), 1000);
    },
    
    /**
     * Perform route search
     * @param {string} query - Search query
     */
    performRouteSearch: function(query) {
        window.TripWeather.Services.RoutePersistence.searchRoutes(query)
            .then(function(data) {
                window.TripWeather.Managers.Search.displayRouteSearchResults(data);
            })
            .catch(function(error) {
                console.error('Route search error:', error);
                document.getElementById('route-search-results').innerHTML = '<div class="search-no-results">Error performing route search</div>';
            });
    },
    
    /**
     * Display route search results in modal
     * @param {object} data - Search response data
     */
    displayRouteSearchResults: function(data) {
        const resultsContainer = document.getElementById('route-search-results');
        
        if (!data || data.length === 0) {
            resultsContainer.innerHTML = '<div class="search-no-results">No routes found</div>';
            return;
        }
        
        resultsContainer.innerHTML = '';
        
        data.forEach(route => {
            const resultItem = document.createElement('div');
            resultItem.className = 'search-result-item';
            
            const createdDate = route.created ? new Date(route.created).toLocaleDateString() : 'Unknown date';
            
            resultItem.innerHTML = `
                <div class="result-label">${route.name}</div>
                <div class="result-details">Created: ${createdDate}</div>
            `;
            
            resultItem.onclick = function() {
                window.TripWeather.Managers.Search.selectRouteSearchResult(route.id);
            };
            
            resultsContainer.appendChild(resultItem);
        });
    },
    
    /**
     * Handle selection of route search result
     * @param {string} routeId - Route ID
     */
    selectRouteSearchResult: function(routeId) {
        this.hideRouteSearchModal();
        
        // Show loading indicator
        window.TripWeather.Managers.UI.showLoading('persistence-loading-overlay');
        
        // Load the selected route
        window.TripWeather.Services.RoutePersistence.loadRoute(routeId)
            .then(response => {
                window.TripWeather.Managers.UI.hideLoading('persistence-loading-overlay');
                
                if (response) {
                    // Convert waypoints from DTO format
                    const waypoints = window.TripWeather.Services.RoutePersistence.convertWaypointsFromDto(response.waypoints || []);
                    
                    // Clear existing waypoints and add loaded ones
                    window.TripWeather.Managers.Waypoint.clearAllWaypoints();
                    
                    waypoints.forEach(waypoint => {
                        window.TripWeather.Managers.Waypoint.addWaypoint(
                            waypoint.lat,
                            waypoint.lng,
                            null, // No location info needed for loaded waypoints
                            waypoint // Pass the existing waypoint object
                        );
                        
                        // Fetch weather for each waypoint if date and time are available
                        if (waypoint.date && waypoint.time) {
                            window.TripWeather.Managers.Waypoint.fetchWeatherForWaypoint(waypoint);
                        }
                    });
                    
                    // Update current route tracking
                    window.TripWeather.App.currentRoute.id = response.id;
                    window.TripWeather.App.currentRoute.name = response.name;
                    window.TripWeather.App.currentRoute.userId = response.userId;
                    
                    window.TripWeather.Managers.UI.showNotification(
                        `Route "${response.name}" loaded successfully with ${waypoints.length} waypoints!`,
                        5000,
                        'success'
                    );
                    console.log('Route loaded successfully:', response);
                    
                    // Automatically calculate route after loading
                    window.TripWeather.Managers.Route.calculateRoute();
                } else {
                    window.TripWeather.Managers.UI.showToast(
                        'Route not found with the provided ID.',
                        'warning'
                    );
                }
            })
            .catch(error => {
                window.TripWeather.Managers.UI.hideLoading('persistence-loading-overlay');
                window.TripWeather.Managers.UI.showToast(
                    `Failed to load route: ${error.message}`,
                    'error'
                );
                console.error('Error loading route:', error);
            });
    }
};
