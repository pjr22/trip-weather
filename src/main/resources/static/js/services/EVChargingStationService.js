/**
 * EV Charging Station Service
 * Handles API communication with the backend EV charging station endpoint
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Services = window.TripWeather.Services || {};

window.TripWeather.Services.EVChargingStation = {
    
    /**
     * Get EV charging stations along a route
     * @param {object} routeData - Route data including geometry
     * @param {object} parameters - Search parameters (optional, uses defaults if not provided)
     * @returns {Promise} - Promise resolving to array of stations in GeoJSON format
     */
    getStationsAlongRoute: function(routeData, parameters) {
        if (!routeData || !routeData.geometry || routeData.geometry.length === 0) {
            return Promise.reject(new Error('No route geometry available'));
        }
        
        // Default parameters for EV charging search
        const defaultParams = {
            format: 'geojson',
            distance: 1.0,
            fuel_type: 'ELEC',
            status: 'E',
            access: 'public',
            ev_charging_level: 'dc_fast',
            ev_connector_type: 'J1772COMBO',
            limit: 100
        };
        
        // Merge with provided parameters
        const searchParams = Object.assign({}, defaultParams, parameters || {});
        
        // Prepare the request body
        const requestBody = {
            route: routeData.geometry,
            parameters: searchParams
        };
        
        return window.TripWeather.Utils.Helpers.httpPost('/api/ev-charging/stations', requestBody)
            .then(function(response) {
                if (response && response.features) {
                    return response.features;
                }
                return [];
            })
            .catch(function(error) {
                console.error('Error fetching EV charging stations:', error);
                throw error;
            });
    },
    
    /**
     * Format station data for display
     * @param {object} station - Station data from API (GeoJSON feature)
     * @returns {object} - Formatted station data
     */
    formatStationData: function(station) {
        const props = station.properties || {};
        
        return {
            id: props.id || station.id,
            name: props.station_name || 'Unknown Station',
            address: this.formatAddress(props),
            phone: props.station_phone || null,
            network: props.ev_network || 'Unknown',
            accessDaysTime: props.access_days_time || null,
            evConnectorTypes: props.ev_connector_types,
            evDCFastNum: props.ev_dc_fast_num || 0,
            evLevel1Num: props.ev_level1_evse_num || 0,
            evLevel2Num: props.ev_level2_evse_num || 0,
            latitude: station.geometry.coordinates[1],
            longitude: station.geometry.coordinates[0],
            facilityType: props.facility_type || null,
            geocodeStatus: props.geocode_status || null
        };
    },
    
    /**
     * Format address from station properties
     * @param {object} props - Station properties
     * @returns {string} - Formatted address
     */
    formatAddress: function(props) {
        const parts = [];
        
        if (props.street_address) {
            parts.push(props.street_address);
        }
        
        const cityState = [];
        if (props.city) cityState.push(props.city);
        if (props.state) cityState.push(props.state);
        if (props.zip) cityState.push(props.zip);
        
        if (cityState.length > 0) {
            parts.push(cityState.join(', '));
        }
        
        return parts.join('\n') || 'Address not available';
    },
        
    /**
     * Create popup content HTML for a station
     * @param {object} station - Formatted station data
     * @returns {string} - HTML content for popup
     */
    createStationPopupContent: function(station) {
        const lightningIcon = '⚡';
        let html = '<div class="ev-station-popup">';
        html += `<h4>${this.escapeHtml(station.name)}</h4>`;
        html += '<div class="station-details">';
        
        // Address
        html += '<div class="station-detail-row">';
        html += '<span class="station-detail-label">Address:</span>';
        html += '</div>';
        html += '<div class="station-address">';
        html += this.escapeHtml(station.address).replace(/\n/g, '<br>');
        html += '</div>';
        
        // Network
        if (station.network && station.network !== 'Unknown') {
            html += '<div class="station-detail-row">';
            html += '<span class="station-detail-label">Network:</span>';
            html += `<span class="station-detail-value">${this.escapeHtml(station.network)}</span>`;
            html += '</div>';
        }
        
        // Charging ports summary
        html += '<div class="station-detail-row">';
        html += '<span class="station-detail-label">DC Fast Chargers:</span>';
        html += `<span class="station-detail-value">${station.evDCFastNum}</span>`;
        html += '</div>';
        
        if (station.evLevel2Num > 0) {
            html += '<div class="station-detail-row">';
            html += '<span class="station-detail-label">Level 2 Chargers:</span>';
            html += `<span class="station-detail-value">${station.evLevel2Num}</span>`;
            html += '</div>';
        }
        
        // Connector types
        if (station.evConnectorTypes && station.evConnectorTypes.length > 0) {
            html += '<div class="connector-types">';
            html += '<span class="station-detail-label">Connectors:</span><br>';
            station.evConnectorTypes.forEach(function(type) {
                html += `<span class="connector-type">${this.escapeHtml(this.formatConnectorType(type))}</span>`;
            }.bind(this));
            html += '</div>';
        }
        
        // Access hours
        if (station.accessDaysTime) {
            html += '<div class="station-detail-row" style="margin-top: 8px;">';
            html += '<span class="station-detail-label">Hours:</span>';
            html += `<span class="station-detail-value">${this.escapeHtml(station.accessDaysTime)}</span>`;
            html += '</div>';
        }
        
        // Phone
        if (station.phone) {
            html += '<div class="station-detail-row">';
            html += '<span class="station-detail-label">Phone:</span>';
            html += `<span class="station-detail-value">${this.escapeHtml(station.phone)}</span>`;
            html += '</div>';
        }
        
        html += '</div>';
        
        // Add to waypoints button
        html += '<div class="ev-station-actions">';
        html += `<button onclick="window.TripWeather.Managers.EVChargingStation.addStationAsWaypoint('${station.id}')" class="ev-add-waypoint-btn">Add To Waypoints</button>`;
        html += '</div>';
        
        html += '</div>';
        
        return html;
    },
    
    /**
     * Format connector type for display
     * @param {string} type - Connector type code
     * @returns {string} - Human-readable connector type
     */
    formatConnectorType: function(type) {
        const connectorNames = {
            'J1772': 'J1772',
            'J1772COMBO': 'CCS (J1772 Combo)',
            'CHADEMO': 'CHAdeMO',
            'TESLA': 'Tesla',
            'NEMA520': 'NEMA 5-20',
            'NEMA1450': 'NEMA 14-50',
            'NEMA515': 'NEMA 5-15'
        };
        
        return connectorNames[type] || type;
    },
    
    /**
     * Escape HTML special characters
     * @param {string} str - String to escape
     * @returns {string} - Escaped string
     */
    escapeHtml: function(str) {
        if (!str) return '';
        
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }
};
