/**
 * Search Manager
 * Handles location search functionality and modal management
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Search = {
    
    // Search state
    searchDebounceTimer: null,
    
    /**
     * Initialize search functionality
     */
    initialize: function() {
        const modal = document.getElementById('search-modal');
        const btn = document.getElementById('search-btn');
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
        const replacingWaypointId = window.TripWeather.Managers.Waypoint.getReplacingWaypointId();
        
        if (replacingWaypointId !== null) {
            this.replaceWaypointLocationFromSearch(replacingWaypointId, lat, lng, locationName, feature);
            window.TripWeather.Managers.Waypoint.setReplacingWaypointId(null);
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
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.id === waypoint.id);
        if (index !== -1) {
            const marker = window.TripWeather.Managers.Waypoint.waypointMarkers[index];
            if (marker && window.TripWeather.Managers.WaypointRenderer) {
                window.TripWeather.Managers.WaypointRenderer.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
    },

    /**
     * Replace waypoint location from search result
     * @param {number} waypointId - ID of waypoint to replace
     * @param {number} lat - New latitude
     * @param {number} lng - New longitude
     * @param {string} locationName - Location name
     * @param {object} feature - GeoJSON feature object
     */
    replaceWaypointLocationFromSearch: function(waypointId, lat, lng, locationName, feature) {
        // Extract location information from search result
        const locationInfo = window.TripWeather.Services.Location.extractLocationFromFeature(feature);
        
        // Replace waypoint with pre-fetched location data
        window.TripWeather.Managers.Waypoint.replaceWaypointLocation(waypointId, lat, lng, locationInfo);
    },

    /**
     * Search for new location for specific waypoint
     * @param {number} waypointId - ID of waypoint to search for
     */
    searchNewLocationForWaypoint: function(waypointId) {
        window.TripWeather.Managers.Waypoint.setReplacingWaypointId(waypointId);
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
    }
};
