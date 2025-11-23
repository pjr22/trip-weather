/**
 * Layer Manager
 * Handles map layers and overlays (weather, etc.)
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Layer = {
    
    // Configuration
    wmsUrl: 'https://digital.weather.gov/ndfd/wms',
    activeLayerName: null,
    activeLayer: null,
    selectedWaypointSequence: null,
    
    // Available layers
    layers: {
        'temperature': {
            name: 'Temperature',
            layerId: 'ndfd.conus.t',
            opacity: 0.6
        },
        'precipitation': {
            name: 'Chance of Precipitation',
            layerId: 'ndfd.conus.pop12',
            opacity: 0.6
        },
        'wind': {
            name: 'Wind',
            layerId: 'ndfd.conus.windspd',
            opacity: 0.6
        }
    },

    // Cache for valid times to avoid repeated API calls
    validTimesCache: {},

    /**
     * Initialize layer manager
     */
    initialize: function() {
        console.log('Initializing Layer Manager...');
        this.defineRetryingLayer();
        this.setupMapEvents();
        this.setupUI(); // Only sets up modal events, control added via map
        this.setupOtherLayersList();
        
        // Cache for layer instances
        this.layerInstances = {};
        
        // Fetch available layers from backend
        this.fetchAvailableLayers();
    },

    /**
     * Define the Retrying WMS Layer class
     */
    defineRetryingLayer: function() {
        L.TileLayer.WMS.Retrying = L.TileLayer.WMS.extend({
            options: {
                maxRetries: 10,
                retryDelay: 1000,
                errorTileUrl: null // Optional: image to show when all retries fail
            },

            createTile: function(coords, done) {
                const tile = document.createElement('img');
                const url = this.getTileUrl(coords);
                
                tile._retries = 0;
                tile._originalUrl = url;

                L.DomEvent.on(tile, 'load', L.Util.bind(this._tileOnLoad, this, done, tile));
                L.DomEvent.on(tile, 'error', L.Util.bind(this._customTileOnError, this, done, tile));

                if (this.options.crossOrigin || this.options.crossOrigin === '') {
                    tile.crossOrigin = this.options.crossOrigin === true ? '' : this.options.crossOrigin;
                }

                tile.alt = '';
                tile.setAttribute('role', 'presentation');
                tile.src = url;

                return tile;
            },

            _customTileOnError: function(done, tile, e) {
                if (tile._retries < this.options.maxRetries) {
                    tile._retries++;
                    const delay = this.options.retryDelay * Math.pow(1.5, tile._retries - 1); // Exponential backoff
                    
                    console.warn(`Tile load error, retrying (${tile._retries}/${this.options.maxRetries}) in ${delay}ms:`, tile._originalUrl);
                    
                    setTimeout(() => {
                        // Add timestamp to bypass browser cache for the retry if it was a network error
                        const separator = tile._originalUrl.includes('?') ? '&' : '?';
                        tile.src = tile._originalUrl + separator + '_retry=' + tile._retries + '_' + Date.now();
                    }, delay);
                } else {
                    // All retries failed
                    console.error('Tile failed to load after retries:', tile._originalUrl);
                    this._tileOnError(done, tile, e);
                }
            }
        });
    },

    /**
     * Setup map event listeners and controls
     */
    setupMapEvents: function() {
        // We need to wait for the map to be initialized
        const checkMapInterval = setInterval(() => {
            const map = window.TripWeather.Managers.Map.getMap();
            if (map) {
                clearInterval(checkMapInterval);
                
                // Listen for popup open/close events
                map.on('popupopen', this.handlePopupOpen.bind(this));
                map.on('popupclose', this.handlePopupClose.bind(this));
                
                // Add Layers Control
                this.addLayersControl(map);
                
                // Add Layer Info Control
                this.addLayerInfoControl(map);
                
                console.log('Layer Manager: Map events registered and control added');
            }
        }, 500);
    },

    /**
     * Add layers control to the map
     * @param {L.Map} map - Leaflet map instance
     */
    addLayersControl: function(map) {
        const LayersControl = L.Control.extend({
            options: {
                position: 'topleft'
            },
            
            onAdd: function(map) {
                const container = L.DomUtil.create('div', 'leaflet-bar layers-control');
                container.title = 'Map Layers';
                container.id = 'layers-btn'; // Set ID for reference if needed
                
                // Use stack icon or similar
                window.TripWeather.Utils.IconLoader.loadSvgIcon('icons/layers.svg', container, 'layers-icon');
                
                // If icon loader fails or as fallback text
                if (container.innerHTML === '') {
                    container.innerHTML = '<span>Layers</span>';
                }

                L.DomEvent.on(container, 'click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    L.DomEvent.preventDefault(e);
                    window.TripWeather.Managers.Layer.showLayersModal();
                });
                
                return container;
            }
        });
        
        map.addControl(new LayersControl());
    },

    /**
     * Add layer info control to the map
     * @param {L.Map} map - Leaflet map instance
     */
    addLayerInfoControl: function(map) {
        const LayerInfoControl = L.Control.extend({
            options: {
                position: 'bottomleft'
            },
            
            onAdd: function(map) {
                const container = L.DomUtil.create('div', 'layer-info-control');
                container.id = 'layer-info-display';
                container.style.display = 'none'; // Hidden by default
                return container;
            }
        });
        
        map.addControl(new LayerInfoControl());
    },

    /**
     * Update the layer info display
     * @param {string} layerName - Name of the layer
     * @param {string} displayTime - Formatted time string
     */
    updateLayerInfoDisplay: function(layerName, displayTime) {
        const container = document.getElementById('layer-info-display');
        if (container) {
            container.innerHTML = `
                <span class="layer-info-label">${layerName}</span>
                <span class="layer-info-time">${displayTime}</span>
            `;
            container.style.display = 'block';
        }
    },

    /**
     * Setup UI controls (Modal)
     */
    setupUI: function() {
        // Setup modal interactions
        const modal = document.getElementById('layers-modal');
        if (modal) {
            const closeBtn = modal.querySelector('.close');
            if (closeBtn) {
                closeBtn.addEventListener('click', this.hideLayersModal.bind(this));
            }
            
            // Close on click outside
            window.addEventListener('click', (event) => {
                if (event.target === modal) {
                    this.hideLayersModal();
                }
            });
            
            // Layer selection buttons
            const layerButtons = modal.querySelectorAll('.layer-select-btn');
            layerButtons.forEach(btn => {
                btn.addEventListener('click', (e) => {
                    const layerKey = e.target.dataset.layer;
                    this.selectLayer(layerKey);
                    this.hideLayersModal();
                });
            });

            // Other layer dropdown
            const otherLayerSelect = document.getElementById('other-layer-select');
            const otherLayerBtn = document.getElementById('other-layer-btn');
            if (otherLayerBtn && otherLayerSelect) {
                otherLayerBtn.addEventListener('click', () => {
                    const layerId = otherLayerSelect.value;
                    if (layerId) {
                        this.selectCustomLayer(layerId);
                        this.hideLayersModal();
                    }
                });
            }
        }
    },
    /**
     * Fetch available layers from backend
     */
    fetchAvailableLayers: function() {
        const self = this;
        
        fetch('/api/wms/layers')
            .then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                console.log('Available WMS layers:', data);
                self.populateOtherLayersDropdown(data);
            })
            .catch(error => {
                console.error('Error fetching WMS layers:', error);
                // Fallback to hardcoded layers if API call fails
                self.populateFallbackLayers();
            });
    },

    /**
     * Fetch valid times for a specific layer from backend
     * @param {string} layerName - Name of the layer
     * @returns {Promise} - Promise that resolves to array of valid times
     */
    fetchValidTimes: function(layerName) {
        const self = this;
        
        // Check cache first
        if (this.validTimesCache[layerName]) {
            return Promise.resolve(this.validTimesCache[layerName]);
        }
        
        return fetch(`/api/wms/layer/validTimes?layerName=${encodeURIComponent(layerName)}`)
            .then(response => {
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                console.log(`Valid times for ${layerName}:`, data);
                // Cache the valid times
                self.validTimesCache[layerName] = data;
                return data;
            })
            .catch(error => {
                console.error(`Error fetching valid times for ${layerName}:`, error);
                // Return empty array as fallback
                return [];
            });
    },

    /**
     * Find the nearest valid time to the target time
     * @param {Array} validTimes - Array of valid time strings in format "yyyy-MM-dd'T'HH:mm"
     * @param {Date} targetTime - Target time to find nearest valid time for
     * @returns {string|null} - Nearest valid time string or null if no valid times
     */
    findNearestValidTime: function(validTimes, targetTime) {
        if (!validTimes || validTimes.length === 0) {
            return null;
        }
        
        const targetTimeMs = targetTime.getTime();
        let nearestTime = validTimes[0];
        let smallestDiff = Math.abs(new Date(validTimes[0]).getTime() - targetTimeMs);
        
        for (let i = 1; i < validTimes.length; i++) {
            const validTimeMs = new Date(validTimes[i]).getTime();
            const diff = Math.abs(validTimeMs - targetTimeMs);
            
            if (diff < smallestDiff) {
                smallestDiff = diff;
                nearestTime = validTimes[i];
            }
        }
        
        return nearestTime;
    },

    /**
     * Populate "Other" layers dropdown with data from backend
     */
    populateOtherLayersDropdown: function(layersData) {
        const select = document.getElementById('other-layer-select');
        if (!select) return;

        // Clear existing options except the first one
        while (select.children.length > 1) {
            select.removeChild(select.lastChild);
        }

        // Add layers from backend
        for (const [layerId, layerTitle] of Object.entries(layersData)) {
            // Skip the main layers that already have dedicated buttons
            if (layerId === 'ndfd.conus.t' || layerId === 'ndfd.conus.pop12' || layerId === 'ndfd.conus.windspd') {
                continue;
            }
            
            const option = document.createElement('option');
            option.value = layerId;
            option.textContent = layerTitle;
            select.appendChild(option);
        }
    },

    /**
     * Fallback method to populate dropdown with hardcoded layers
     */
    populateFallbackLayers: function() {
        const select = document.getElementById('other-layer-select');
        if (!select) return;

        // Clear existing options except the first one
        while (select.children.length > 1) {
            select.removeChild(select.lastChild);
        }

        // Common useful layers from NDFD (fallback)
        const fallbackLayers = [
            { id: 'ndfd.conus.apparentt', name: 'Apparent Temperature' },
            { id: 'ndfd.conus.apparentt.points', name: 'Apparent Temperature (Points)' },
            { id: 'ndfd.conus.td', name: 'Dew Point' },
            { id: 'ndfd.conus.td.points', name: 'Dew Point (Points)' },
            { id: 'ndfd.conus.rh', name: 'Relative Humidity' },
            { id: 'ndfd.conus.rh.points', name: 'Relative Humidity (Points)' },
            { id: 'ndfd.conus.sky', name: 'Sky Cover' },
            { id: 'ndfd.conus.sky.points', name: 'Sky Cover (Points)' },
            { id: 'ndfd.conus.windgust', name: 'Wind Gust' },
            { id: 'ndfd.conus.windgust.points', name: 'Wind Gust (Points)' },
            { id: 'ndfd.conus.winddir', name: 'Wind Direction' },
            { id: 'ndfd.conus.winddir.points', name: 'Wind Direction (Points)' },
            { id: 'ndfd.conus.qpf', name: 'Quantitative Precip. Forecast' },
            { id: 'ndfd.conus.qpf.points', name: 'Quantitative Precip. Forecast (Points)' },
            { id: 'ndfd.conus.snowamt', name: 'Snow Amount' },
            { id: 'ndfd.conus.snowamt.points', name: 'Snow Amount (Points)' },
            { id: 'ndfd.conus.iceaccum', name: 'Ice Accumulation' },
            { id: 'ndfd.conus.iceaccum.points', name: 'Ice Accumulation (Points)' },
            { id: 'ndfd.conus.wwa', name: 'Watches, Warnings, Advisories' },
            { id: 'ndfd.conus.wwa.points', name: 'Watches, Warnings, Advisories (Points)' },
            { id: 'ndfd.conus.hazards', name: 'Hazards' }
        ];

        fallbackLayers.forEach(layer => {
            const option = document.createElement('option');
            option.value = layer.id;
            option.textContent = layer.name;
            select.appendChild(option);
        });
    },


    /**
     * Populate the "Other" layers dropdown
     */
    setupOtherLayersList: function() {
        const select = document.getElementById('other-layer-select');
        if (!select) return;

        // Common useful layers from NDFD
        const otherLayers = [
            { id: 'ndfd.conus.apparentt', name: 'Apparent Temperature' },
            { id: 'ndfd.conus.apparentt.points', name: 'Apparent Temperature (Points)' },
            { id: 'ndfd.conus.t', name: 'Temperature' },
            { id: 'ndfd.conus.t.points', name: 'Temperature (Points)' },
            { id: 'ndfd.conus.td', name: 'Dew Point' },
            { id: 'ndfd.conus.td.points', name: 'Dew Point (Points)' },
            { id: 'ndfd.conus.rh', name: 'Relative Humidity' },
            { id: 'ndfd.conus.rh.points', name: 'Relative Humidity (Points)' },
            { id: 'ndfd.conus.sky', name: 'Sky Cover' },
            { id: 'ndfd.conus.sky.points', name: 'Sky Cover (Points)' },
            { id: 'ndfd.conus.windspd', name: 'Wind Speed' },
            { id: 'ndfd.conus.windspd.points', name: 'Wind Speed (Points)' },
            { id: 'ndfd.conus.windgust', name: 'Wind Gust' },
            { id: 'ndfd.conus.windgust.points', name: 'Wind Gust (Points)' },
            { id: 'ndfd.conus.winddir', name: 'Wind Direction' },
            { id: 'ndfd.conus.winddir.points', name: 'Wind Direction (Points)' },
            { id: 'ndfd.conus.pop12', name: 'Precipitation Prob. (12hr)' },
            { id: 'ndfd.conus.pop12.points', name: 'Precipitation Prob. (12hr Points)' },
            { id: 'ndfd.conus.qpf', name: 'Quantitative Precip. Forecast' },
            { id: 'ndfd.conus.qpf.points', name: 'Quantitative Precip. Forecast (Points)' },
            { id: 'ndfd.conus.snowamt', name: 'Snow Amount' },
            { id: 'ndfd.conus.snowamt.points', name: 'Snow Amount (Points)' },
            { id: 'ndfd.conus.iceaccum', name: 'Ice Accumulation' },
            { id: 'ndfd.conus.iceaccum.points', name: 'Ice Accumulation (Points)' },
            { id: 'ndfd.conus.wwa', name: 'Watches, Warnings, Advisories' },
            { id: 'ndfd.conus.wwa.points', name: 'Watches, Warnings, Advisories (Points)' },
            { id: 'ndfd.conus.hazards', name: 'Hazards' } // Kept if valid, though wwa usually covers it
        ];

        otherLayers.forEach(layer => {
            const option = document.createElement('option');
            option.value = layer.id;
            option.textContent = layer.name;
            select.appendChild(option);
        });
    },

    /**
     * Handle popup open event
     * @param {L.PopupEvent} e - Popup event
     */
    handlePopupOpen: function(e) {
        const marker = e.popup._source;
        if (marker && marker.waypointSequence) {
            this.selectedWaypointSequence = marker.waypointSequence;
            this.updateLayerForSelectedWaypoint();
        }
    },

    /**
     * Handle popup close event
     * @param {L.PopupEvent} e - Popup event
     */
    handlePopupClose: function(e) {
        const marker = e.popup._source;
        if (marker && marker.waypointSequence === this.selectedWaypointSequence) {
            this.selectedWaypointSequence = null;
            this.hideActiveLayer(); // Requirement: hide layer if no waypoint selected
        }
    },

    /**
     * Select a standard layer
     * @param {string} layerKey - Key from layers config (temperature, precipitation, wind, none)
     */
    selectLayer: function(layerKey) {
        if (layerKey === 'none') {
            this.activeLayerName = null;
            this.activeLayerConfig = null; // Clear config too
            this.removeActiveLayer();
            window.TripWeather.Managers.UI.showToast('Layer removed', 'info');
            
            // Hide info display
            const infoDisplay = document.getElementById('layer-info-display');
            if (infoDisplay) {
                infoDisplay.style.display = 'none';
            }
            return;
        }

        if (this.layers[layerKey]) {
            this.activeLayerName = layerKey;
            this.activeLayerConfig = this.layers[layerKey];
            // If a waypoint is already selected, show the layer immediately
            if (this.selectedWaypointSequence) {
                this.updateLayerForSelectedWaypoint();
                window.TripWeather.Managers.UI.showToast(`Selected layer: ${this.layers[layerKey].name}`, 'info');
            } else {
                window.TripWeather.Managers.UI.showToast(`${this.layers[layerKey].name} layer selected. Select a waypoint to view data.`, 'info');
            }
        }
    },

    /**
     * Select a custom layer from the dropdown
     * @param {string} layerId - WMS layer ID
     */
    selectCustomLayer: function(layerId) {
        this.activeLayerName = 'custom';
        this.activeLayerConfig = {
            name: layerId,
            layerId: layerId,
            opacity: 0.6
        };
        
        if (this.selectedWaypointSequence) {
            this.updateLayerForSelectedWaypoint();
            window.TripWeather.Managers.UI.showToast(`Selected layer: ${layerId}`, 'info');
        } else {
            window.TripWeather.Managers.UI.showToast(`${layerId} layer selected. Select a waypoint to view data.`, 'info');
        }
    },

    /**
     * Update layer time based on selected waypoint
     */
    updateLayerForSelectedWaypoint: function() {
        if (!this.activeLayerName || !this.selectedWaypointSequence) return;

        const waypoint = window.TripWeather.Managers.Waypoint.getWaypoint(this.selectedWaypointSequence);
        if (!waypoint) return;

        this.updateLayerTime(waypoint);
    },

    /**
     * Remove the currently active layer
     */
    removeActiveLayer: function() {
        if (this.activeLayer) {
            const map = window.TripWeather.Managers.Map.getMap();
            if (map) {
                map.removeLayer(this.activeLayer);
            }
            // We keep it in layerInstances, but we unset activeLayer reference if we are truly clearing
            // However, selectLayer calls this before setting a new one.
            // If we want to 'deselect', we set activeLayer = null.
            // But updateLayerTime relies on activeLayer being set to check for reuse.
            // Actually, updateLayerTime handles the switch logic itself now.
            
            // If this function is called from 'hideActiveLayer' or 'selectLayer(none)', we should nullify.
            this.activeLayer = null;
        }
    },

    /**
     * Update the layer with time from a specific waypoint
     * @param {object} waypoint - Waypoint object
     */
    updateLayerTime: function(waypoint) {
        // If this isn't the selected waypoint, ignore
        if (waypoint.sequence !== this.selectedWaypointSequence) return;
        if (!this.activeLayerName || !this.activeLayerConfig) return;

        const map = window.TripWeather.Managers.Map.getMap();
        if (!map) return;

        // Convert waypoint date/time to Date object
        let targetDate = new Date(); // Default to now
        
        if (waypoint.date && waypoint.time) {
            const dateTimeStr = `${waypoint.date} ${waypoint.time}`;
            const parsedDate = new Date(dateTimeStr);
            
            if (!isNaN(parsedDate.getTime())) {
                targetDate = parsedDate;
            }
        }
        
        // Convert to UTC for comparison with valid times from backend
        // The valid times from backend are in UTC format, so we need to compare with UTC time
        const targetDateUTC = new Date(targetDate.getTime() + (targetDate.getTimezoneOffset() * 60000));

        // Fetch valid times for this layer
        this.fetchValidTimes(this.activeLayerConfig.layerId)
            .then(validTimes => {
                if (!validTimes || validTimes.length === 0) {
                    console.warn(`No valid times available for layer ${this.activeLayerConfig.layerId}`);
                    window.TripWeather.Managers.UI.showToast(
                        `No valid times available for this layer`,
                        'warning'
                    );
                    return;
                }

                // Find nearest valid time to target date (in UTC)
                const nearestValidTime = this.findNearestValidTime(validTimes, targetDateUTC);
                
                if (!nearestValidTime) {
                    console.warn(`Could not find a valid time for layer ${this.activeLayerConfig.layerId}`);
                    return;
                }

                // Check if we already have an active layer for this configuration
                if (this.activeLayer && this.activeLayer.wmsParams.layers === this.activeLayerConfig.layerId) {
                    // Same layer, just update params
                    console.log(`Updating existing layer ${this.activeLayerConfig.layerId} time to ${nearestValidTime}`);
                    this.activeLayer.setParams({ vtit: nearestValidTime });
                } else {
                    // Different layer or no layer, create new
                    this.removeActiveLayer();

                    // Check cache first
                    const cacheKey = this.activeLayerConfig.layerId;
                    let layer = this.layerInstances[cacheKey];

                    if (!layer) {
                        console.log(`Creating new WMS layer ${this.activeLayerConfig.layerId}`);
                        layer = new L.TileLayer.WMS.Retrying(this.wmsUrl, {
                            layers: this.activeLayerConfig.layerId,
                            format: 'image/png',
                            transparent: true,
                            version: '1.3.0',
                            vtit: nearestValidTime,
                            opacity: this.activeLayerConfig.opacity,
                            attribution: 'National Weather Service',
                            maxRetries: 10,
                            retryDelay: 1000
                        });
                        this.layerInstances[cacheKey] = layer;
                    } else {
                        console.log(`Reusing cached layer ${this.activeLayerConfig.layerId}`);
                        layer.setParams({ vtit: nearestValidTime });
                    }
                    
                    this.activeLayer = layer;
                    this.activeLayer.addTo(map);
                }
                
                // Update info display with both waypoint time and actual valid time used
                if (window.TripWeather.Managers.WaypointRenderer && typeof window.TripWeather.Managers.WaypointRenderer.formatWaypointTime === 'function') {
                    const waypointDisplayTime = window.TripWeather.Managers.WaypointRenderer.formatWaypointTime(waypoint, false);
                    
                    this.updateLayerInfoDisplay(
                        this.activeLayerConfig.name,
                        `${waypointDisplayTime} (using: ${nearestValidTime} UTC)`
                    );
                }
            })
            .catch(error => {
                console.error(`Error updating layer time:`, error);
                window.TripWeather.Managers.UI.showToast(
                    `Failed to update layer data: ${error.message}`,
                    'error'
                );
            });
    },


    /**
     * Hide the active layer (without unselecting it)
     */
    hideActiveLayer: function() {
        this.removeActiveLayer();
        
        // Hide info display
        const infoDisplay = document.getElementById('layer-info-display');
        if (infoDisplay) {
            infoDisplay.style.display = 'none';
        }
    },

    /**
     * Show the layers modal
     */
    showLayersModal: function() {
        const modal = document.getElementById('layers-modal');
        if (modal) {
            modal.style.display = 'block';
        }
    },

    /**
     * Hide the layers modal
     */
    hideLayersModal: function() {
        const modal = document.getElementById('layers-modal');
        if (modal) {
            modal.style.display = 'none';
        }
    }
};
