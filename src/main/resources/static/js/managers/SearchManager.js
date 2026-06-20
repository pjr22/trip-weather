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
     * Perform location search. Phase 3b: when the viewer is authenticated,
     * fires a parallel /api/favorites?search=q call along the same debounce
     * so matching favorites surface at the top of the dropdown ahead of the
     * geocode results. Anonymous users see exactly today's geocode-only path.
     *
     * Uses Promise.allSettled so a transient favorites failure doesn't blank
     * the geocode results (and vice versa).
     *
     * @param {string} query - Search query
     */
    performSearch: function(query) {
        const auth = window.TripWeather.Services.Auth;
        const isAuthed = auth && auth.getCurrentUser();

        const geocodePromise = window.TripWeather.Services.Location.searchLocations(query);
        const favoritesPromise = isAuthed
            ? window.TripWeather.Services.Favorites.list(query)
            : Promise.resolve([]);

        Promise.allSettled([favoritesPromise, geocodePromise]).then(function(results) {
            const favoritesResult = results[0];
            const geocodeResult = results[1];

            const favorites = favoritesResult.status === 'fulfilled'
                ? (favoritesResult.value || [])
                : [];
            const geocodeData = geocodeResult.status === 'fulfilled'
                ? geocodeResult.value
                : null;

            if (favoritesResult.status === 'rejected') {
                console.warn('Favorites lookup failed during search:', favoritesResult.reason);
            }
            if (geocodeResult.status === 'rejected') {
                console.error('Geocode search failed:', geocodeResult.reason);
            }

            window.TripWeather.Managers.Search.displaySearchResults(favorites, geocodeData);
        });
    },

    /**
     * Display search results in modal. Favorites (when any) render first
     * under a "Favorites" section header, each prefixed with a small filled-
     * heart icon to make their provenance obvious. Geocode results follow
     * under their existing section.
     *
     * @param {Array} favorites - matching favorites for the current query (may be empty)
     * @param {object} geocodeData - GeoJSON-style response from forward geocoder (may be null)
     */
    displaySearchResults: function(favorites, geocodeData) {
        const resultsContainer = document.getElementById('search-results');
        resultsContainer.innerHTML = '';

        const hasFavorites = favorites && favorites.length > 0;
        const hasGeocode = geocodeData && geocodeData.features && geocodeData.features.length > 0;

        if (!hasFavorites && !hasGeocode) {
            resultsContainer.innerHTML = '<div class="search-no-results">No results found</div>';
            return;
        }

        if (hasFavorites) {
            const favHeader = document.createElement('div');
            favHeader.className = 'search-section-header';
            favHeader.textContent = 'Favorites';
            resultsContainer.appendChild(favHeader);

            favorites.forEach(function(fav) {
                resultsContainer.appendChild(this.buildFavoriteResultItem(fav));
            }.bind(this));
        }

        if (hasGeocode) {
            if (hasFavorites) {
                const geoHeader = document.createElement('div');
                geoHeader.className = 'search-section-header';
                geoHeader.textContent = 'Search results';
                resultsContainer.appendChild(geoHeader);
            }

            geocodeData.features.forEach(feature => {
                const coordinates = feature.geometry.coordinates;
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
        }
    },

    /**
     * Build a search-dropdown row representing a favorite. Visually similar
     * to a geocode result but prefixed with a small filled-red heart so the
     * provenance is unmistakable. Clicking it appends the favorite as the
     * next waypoint of the current route — same effect as the manager
     * modal's Add-to-route action.
     */
    buildFavoriteResultItem: function(fav) {
        const item = document.createElement('div');
        item.className = 'search-result-item search-result-favorite';

        // Inline SVG heart so we don't need an async icon load per row.
        const heart = document.createElement('span');
        heart.className = 'search-result-favorite-heart';
        heart.innerHTML = ''
            + '<svg viewBox="0 0 24 24" fill="currentColor">'
            + '<path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>'
            + '</svg>';

        const text = document.createElement('div');
        text.className = 'search-result-favorite-text';

        const labelDiv = document.createElement('div');
        labelDiv.className = 'result-label';
        labelDiv.textContent = fav.label;

        const detailsDiv = document.createElement('div');
        detailsDiv.className = 'result-details';
        detailsDiv.textContent = fav.locationName || '';

        text.appendChild(labelDiv);
        text.appendChild(detailsDiv);

        item.appendChild(heart);
        item.appendChild(text);

        item.addEventListener('click', function() {
            window.TripWeather.Managers.Search._addFavoriteToRoute(fav);
        });

        return item;
    },

    /**
     * Add a favorite to the current route as the next waypoint. Same effect
     * as the manager modal's Add-to-route action; called from the favorite-
     * search-row click handler.
     */
    _addFavoriteToRoute: function(fav) {
        this.hideModal();
        const waypointMgr = window.TripWeather.Managers.Waypoint;
        if (!waypointMgr || typeof waypointMgr.addWaypoint !== 'function') {
            window.Toast.show('Waypoint manager not available', 'error');
            return;
        }
        // Pass through the favorite's saved timezone fields so the new
        // waypoint renders times correctly without a fresh /api/timezone
        // round-trip. Missing fields stay as empty string — addWaypoint
        // already handles that case.
        const locationInfo = {
            locationName: fav.locationName || '',
            timezoneName: fav.timezoneName || '',
            timezoneStdOffset: fav.timezoneStdOffset || '',
            timezoneDstOffset: fav.timezoneDstOffset || '',
            timezoneStdAbbr: fav.timezoneStdAbbr || '',
            timezoneDstAbbr: fav.timezoneDstAbbr || ''
        };
        waypointMgr.addWaypoint(fav.latitude, fav.longitude, fav.elevation, locationInfo);
        window.Toast.show('Added "' + fav.label + '" to route', 'success');
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

        // Resolve to snapped point + elevation in one call. Search results
        // are not gated on routability (the user explicitly chose this
        // address), so an off-road result still gets added — at the search
        // coords with terrain elevation.
        window.TripWeather.Services.Location.resolveLocation(lat, lng)
            .then(function(resolution) {
                const useLat = resolution && resolution.snapped ? resolution.snapped.lat : lat;
                const useLng = resolution && resolution.snapped ? resolution.snapped.lng : lng;
                const alt = resolution && resolution.snapped && resolution.snapped.elevation != null
                    ? resolution.snapped.elevation : 0;
                window.TripWeather.Managers.Search._addOrReplaceFromSearch(useLat, useLng, alt, locationName, feature);
                window.TripWeather.Managers.Map.centerOn(useLat, useLng, 13);
            })
            .catch(function(error) {
                console.warn('Failed to resolve location, falling back to search coords:', error);
                window.TripWeather.Managers.Search._addOrReplaceFromSearch(lat, lng, 0, locationName, feature);
                window.TripWeather.Managers.Map.centerOn(lat, lng, 13);
            });
    },

    /** Internal helper for selectSearchResult: dispatches add vs replace
     *  based on the manager's pending-replace state. */
    _addOrReplaceFromSearch: function(lat, lng, alt, locationName, feature) {
        const replacingWaypointSequence = window.TripWeather.Managers.Waypoint.getReplacingWaypointSequence();
        if (replacingWaypointSequence !== null) {
            window.TripWeather.Managers.Search.replaceWaypointLocationFromSearch(replacingWaypointSequence, lat, lng, alt, locationName, feature);
            window.TripWeather.Managers.Waypoint.setReplacingWaypointSequence(null);
        } else {
            const waypoint = window.TripWeather.Managers.Search.addWaypointFromSearch(lat, lng, alt, locationName, feature);
            if (waypoint && window.TripWeather.Managers.WaypointRenderer) {
                window.TripWeather.Managers.WaypointRenderer.openWaypointPopup(waypoint.sequence);
            }
        }
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

        // Create waypoint with pre-fetched location data — search results are
        // user-intentional, so no routability gating (callers used to pass
        // skipValidation=true; that knob has been retired).
        const waypoint = window.TripWeather.Managers.Waypoint.addWaypoint(lat, lng, alt, locationInfo);
        
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
        window.TripWeather.Managers.Waypoint.replaceWaypointLocation(sequence, lat, lng, alt, locationInfo);
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
        // Phase 4 of FAVORITES_AND_ROUTE_MGMT.md: switched from the legacy
        // GET /api/routes/search/{text} (RouteSearchResultDto) to the new
        // GET /api/routes?search=... (RouteSummaryDto). Per-row gating no
        // longer needs userId — the endpoint scopes results to the caller
        // already, so any authenticated viewer can act on any returned row.
        window.TripWeather.Services.RoutePersistence.listRoutes(query)
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
        const isAuthed = !!(auth && auth.getCurrentUser());

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

            // Phase 4 dropped userId from the row shape — the GET /api/routes
            // endpoint already scopes results to the caller's own routes, so
            // every row returned to an authenticated viewer is theirs to act
            // on. Anonymous viewers can't delete (server requires auth) so the
            // button is hidden for them.
            if (isAuthed) {
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

                    // Loading a route replaces any AI-loaded one, so revert the
                    // toolbar button from "AI Results" back to "AI Assist".
                    const aiModal = window.TripWeather.Managers.AiAssistModal;
                    if (aiModal && typeof aiModal.enterAssistMode === 'function') {
                        aiModal.enterAssistMode();
                    }

                    waypoints.forEach(waypoint => {
                        window.TripWeather.Managers.Waypoint.addWaypoint(
                            waypoint.lat,
                            waypoint.lng,
                            waypoint.alt,
                            null, // No location info needed for loaded waypoints
                            waypoint // Existing waypoint object — preserves the saved name
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
