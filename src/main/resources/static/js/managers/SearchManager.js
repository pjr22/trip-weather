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
    initialized: false,

    /**
     * Initialize search functionality
     */
    initialize: function() {
        if (this.initialized) {
            return;
        }
        this.initialized = true;

        const modal = document.getElementById('search-modal');
        const btn = document.getElementById('search-location-btn');
        const closeBtn = document.querySelector('.close');
        const searchInput = document.getElementById('search-input');

        if (btn) {
            btn.addEventListener('click', function() {
                this.showModal();
            }.bind(this));
        }

        if (closeBtn) {
            closeBtn.addEventListener('click', function() {
                this.hideModal();
            }.bind(this));
        }

        if (modal) {
            modal.addEventListener('click', function(event) {
                if (event.target == modal) {
                    this.hideModal();
                }
            }.bind(this));
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
            const coordinates = feature.geometry.coordinates;

            // Use the LocationService to get consistent location naming
            const displayInfo = window.TripWeather.Services.Location.formatLocationDisplay(feature);
            const label = displayInfo.label;
            const details = displayInfo.details;

            // Build result items with createElement + textContent so external strings
            // from the geocoding API can't inject markup.
            const resultItem = document.createElement('div');
            resultItem.className = 'search-result-item';

            const labelDiv = document.createElement('div');
            labelDiv.className = 'result-label';
            labelDiv.textContent = label;

            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'result-details';
            detailsDiv.textContent = details;

            resultItem.appendChild(labelDiv);
            resultItem.appendChild(detailsDiv);

            resultItem.addEventListener('click', function() {
                window.TripWeather.Managers.Search.selectSearchResult(coordinates[1], coordinates[0], label, feature);
            });

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

        // Fetch elevation for the selected location
        window.TripWeather.Services.Location.getElevation(lat, lng)
            .then(function(elevation) {
                const alt = elevation || 0;
                
                // Check if we're replacing a waypoint
                const replacingWaypointSequence = window.TripWeather.Managers.Waypoint.getReplacingWaypointSequence();
                
                if (replacingWaypointSequence !== null) {
                    window.TripWeather.Managers.Search.replaceWaypointLocationFromSearch(replacingWaypointSequence, lat, lng, alt, locationName, feature);
                    window.TripWeather.Managers.Waypoint.setReplacingWaypointSequence(null);
                } else {
                    const waypoint = window.TripWeather.Managers.Search.addWaypointFromSearch(lat, lng, alt, locationName, feature);
                    
                    // Open popup for the newly added waypoint after elevation is retrieved
                    if (waypoint && window.TripWeather.Managers.WaypointRenderer) {
                        window.TripWeather.Managers.WaypointRenderer.openWaypointPopup(waypoint.sequence);
                    }
                }
                
                // Center map on selected location
                window.TripWeather.Managers.Map.centerOn(lat, lng, 13);
            })
            .catch(function(error) {
                console.warn('Failed to fetch elevation, using 0 as default:', error);
                
                // Fallback to 0 elevation if fetch fails
                const alt = 0;
                
                // Check if we're replacing a waypoint
                const replacingWaypointSequence = window.TripWeather.Managers.Waypoint.getReplacingWaypointSequence();
                
                if (replacingWaypointSequence !== null) {
                    window.TripWeather.Managers.Search.replaceWaypointLocationFromSearch(replacingWaypointSequence, lat, lng, alt, locationName, feature);
                    window.TripWeather.Managers.Waypoint.setReplacingWaypointSequence(null);
                } else {
                    const waypoint = window.TripWeather.Managers.Search.addWaypointFromSearch(lat, lng, alt, locationName, feature);
                    
                    // Open popup for the newly added waypoint even if elevation fetch failed
                    if (waypoint && window.TripWeather.Managers.WaypointRenderer) {
                        window.TripWeather.Managers.WaypointRenderer.openWaypointPopup(waypoint.sequence);
                    }
                }
                
                // Center map on selected location
                window.TripWeather.Managers.Map.centerOn(lat, lng, 13);
            });
    },

    /**
     * Add waypoint from search result
     * @param {number} lat - Latitude
     * @param {number} lng - Longitude
     * @param {number} alt - Altitude
     * @param {string} locationName - Location name
     * @param {object} feature - GeoJSON feature object
     * @returns {object} - Created waypoint
     */
    addWaypointFromSearch: function(lat, lng, alt, locationName, feature) {
        // Extract location information from search result
        const locationInfo = window.TripWeather.Services.Location.extractLocationFromFeature(feature);
        
        // Create waypoint with pre-fetched location data, skip validation since search results are already valid
        const waypoint = window.TripWeather.Managers.Waypoint.addWaypoint(lat, lng, alt, locationInfo, null, true);
        
        // No need to call fetchLocationInfo since we already have all the data
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.sequence === waypoint.sequence);
        if (index !== -1) {
            const marker = window.TripWeather.Managers.Waypoint.waypointMarkers[index];
            if (marker && window.TripWeather.Managers.WaypointRenderer) {
                window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
        
        return waypoint;
    },

    /**
     * Replace waypoint location from search result
     * @param {number} sequence - Sequence of waypoint to replace
     * @param {number} lat - New latitude
     * @param {number} lng - New longitude
     * @param {number} alt - New altitude
     * @param {string} locationName - Location name
     * @param {object} feature - GeoJSON feature object
     */
    replaceWaypointLocationFromSearch: function(sequence, lat, lng, alt, locationName, feature) {
        // Extract location information from search result
        const locationInfo = window.TripWeather.Services.Location.extractLocationFromFeature(feature);
        
        // Replace waypoint with pre-fetched location data, skip validation since search results are already valid
        window.TripWeather.Managers.Waypoint.replaceWaypointLocation(sequence, lat, lng, alt, locationInfo, true);
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
            closeBtn.addEventListener('click', function() {
                this.hideRouteSearchModal();
            }.bind(this));
        }

        if (modal) {
            modal.addEventListener('click', function(event) {
                if (event.target == modal) {
                    this.hideRouteSearchModal();
                }
            }.bind(this));
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
     * Display route search results in modal. When the current viewer owns a
     * result row, render an inline 🗑 button that deletes it after confirm.
     * Anonymous viewers and authenticated viewers looking at someone else's
     * route (which only happens via a stale cache — server scopes results)
     * see no delete affordance.
     * @param {object} data - Search response data
     */
    displayRouteSearchResults: function(data) {
        const resultsContainer = document.getElementById('route-search-results');

        if (!data || data.length === 0) {
            resultsContainer.innerHTML = '<div class="search-no-results">No routes found</div>';
            return;
        }

        const auth = window.TripWeather.Services.Auth;
        const currentUser = auth ? auth.getCurrentUser() : null;
        const currentUserId = currentUser ? currentUser.id : null;

        resultsContainer.innerHTML = '';

        data.forEach(route => {
            const createdDate = route.created ? new Date(route.created).toLocaleDateString() : 'Unknown date';

            // Build result items with createElement + textContent. route.name is user-typed
            // text stored in the DB — interpolating it into innerHTML would be a stored-XSS
            // vector.
            const resultItem = document.createElement('div');
            resultItem.className = 'search-result-item';

            const labelDiv = document.createElement('div');
            labelDiv.className = 'result-label';
            labelDiv.textContent = route.name;

            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'result-details';
            detailsDiv.textContent = `Created: ${createdDate}`;

            resultItem.appendChild(labelDiv);
            resultItem.appendChild(detailsDiv);

            resultItem.addEventListener('click', function() {
                window.TripWeather.Managers.Search.selectRouteSearchResult(route.id);
            });

            if (currentUserId && route.userId === currentUserId) {
                // Match the waypoint-row delete button: red background +
                // white SVG icon loaded by IconLoader.
                const deleteBtn = document.createElement('button');
                deleteBtn.type = 'button';
                deleteBtn.className = 'action-btn delete-action route-delete-btn';
                deleteBtn.title = 'Delete this route';
                deleteBtn.setAttribute('aria-label', 'Delete route ' + route.name);

                const iconContainer = document.createElement('span');
                iconContainer.className = 'action-icon-container';
                deleteBtn.appendChild(iconContainer);
                window.TripWeather.Utils.IconLoader.loadSvgIcon(
                    'icons/delete.svg', iconContainer, 'action-icon');

                deleteBtn.addEventListener('click', function(event) {
                    // Click on the trash button must NOT also trigger the
                    // row's "load route" handler.
                    event.stopPropagation();
                    window.TripWeather.Managers.Search.confirmDeleteRoute(route, resultItem);
                });
                resultItem.appendChild(deleteBtn);
            }

            resultsContainer.appendChild(resultItem);
        });
    },

    /**
     * Confirm + delete a route from the search results list. Removes the row
     * on success and toasts the result. Re-runs the live search query when
     * the user is mid-typing so the results stay consistent with the server.
     */
    confirmDeleteRoute: function(route, rowElement) {
        const ui = window.TripWeather.Managers.UI;
        ui.showConfirm(
            'Delete the route "' + route.name + '"? This cannot be undone.',
            function() {
                window.TripWeather.Services.RoutePersistence.deleteRoute(route.id)
                    .then(function() {
                        if (rowElement && rowElement.parentNode) {
                            rowElement.parentNode.removeChild(rowElement);
                        }
                        const remaining = document.querySelectorAll('#route-search-results .search-result-item');
                        if (remaining.length === 0) {
                            const container = document.getElementById('route-search-results');
                            if (container) {
                                container.innerHTML = '<div class="search-no-results">No routes found</div>';
                            }
                        }
                        ui.showToast('Route deleted.', 'success');
                    })
                    .catch(function(err) {
                        if (err && err.status === 404) {
                            ui.showToast('That route is no longer available.', 'warning');
                        } else if (err && err.status === 401) {
                            ui.showToast('Please log in to delete routes.', 'warning');
                        } else {
                            ui.showToast('Could not delete the route. Please try again.', 'error');
                        }
                    });
            },
            null,
            { title: 'Delete route', confirmLabel: 'Delete', danger: true }
        );
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
                    
                    // Sort waypoints by sequence number to ensure correct order
                    waypoints.sort((a, b) => a.sequence - b.sequence);
                    
                    // Clear existing waypoints and add loaded ones
                    window.TripWeather.Managers.Waypoint.clearAllWaypoints();
                    
                    waypoints.forEach(waypoint => {
                        window.TripWeather.Managers.Waypoint.addWaypoint(
                            waypoint.lat,
                            waypoint.lng,
                            waypoint.alt,
                            null, // No location info needed for loaded waypoints
                            waypoint, // Pass the existing waypoint object
                            true // Skip validation for loaded routes
                        );
                        
                        // Fetch weather for each waypoint if date and time are available
                        if (waypoint.date && waypoint.time) {
                            window.TripWeather.Managers.Waypoint.fetchWeatherForWaypoint(waypoint);
                        }
                    });
                    
                    // Update current route tracking
                    window.TripWeather.App.currentRoute.id = response.id;
                    window.TripWeather.App.currentRoute.userId = response.userId;
                    window.TripWeather.App.setCurrentRouteName(response.name);
                    
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
