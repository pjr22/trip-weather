let map;
let waypoints = [];
let waypointMarkers = [];
let userLocationMarker = null;
let replacingWaypointId = null;

const DEFAULT_LAT = 39.8283;
const DEFAULT_LNG = -98.5795;
const DEFAULT_ZOOM = 4;
const USER_ZOOM = 13;

async function loadSvgIcon(iconPath, container, className = '') {
    try {
        const response = await fetch(iconPath);
        const svgText = await response.text();
        const parser = new DOMParser();
        const svgDoc = parser.parseFromString(svgText, 'image/svg+xml');
        const svg = svgDoc.documentElement;
        
        if (className) {
            svg.classList.add(className);
        }
        
        container.innerHTML = '';
        container.appendChild(svg);
        
        return svg;
    } catch (error) {
        console.error('Failed to load SVG icon:', error);
        return null;
    }
}

class Waypoint {
    constructor(id, lat, lng) {
        this.id = id;
        this.lat = lat.toFixed(6);
        this.lng = lng.toFixed(6);
        this.date = '';
        this.time = '';
        this.locationName = '';
        this.weather = null;
        this.weatherLoading = false;
    }
}

function initializeMap(lat, lng, zoom) {
    map = L.map('map').setView([lat, lng], zoom);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
    }).addTo(map);

    userLocationMarker = L.marker([lat, lng]).addTo(map)
        .bindPopup('Your location')
        .openPopup();
    
    map.on('click', onMapClick);
    
    addRecenterControl();
}

function addRecenterControl() {
    const RecenterControl = L.Control.extend({
        options: {
            position: 'topleft'
        },
        
        onAdd: function(map) {
            const container = L.DomUtil.create('div', 'leaflet-bar recenter-control');
            container.title = 'Recenter on my location';
            
            loadSvgIcon('icons/crosshair.svg', container, 'recenter-icon');
            
            L.DomEvent.on(container, 'click', function(e) {
                L.DomEvent.stopPropagation(e);
                L.DomEvent.preventDefault(e);
                recenterOnUserLocation();
            });
            
            return container;
        }
    });
    
    map.addControl(new RecenterControl());
}

function recenterOnUserLocation() {
    if ("geolocation" in navigator) {
        navigator.geolocation.getCurrentPosition(
            function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;
                const currentZoom = map.getZoom();
                
                map.setView([lat, lng], currentZoom);
                
                if (userLocationMarker) {
                    userLocationMarker.setLatLng([lat, lng]);
                    userLocationMarker.openPopup();
                } else {
                    userLocationMarker = L.marker([lat, lng]).addTo(map)
                        .bindPopup('Your location')
                        .openPopup();
                }
            },
            function(error) {
                console.warn('Geolocation error:', error.message);
                alert('Unable to get your current location. Please check your browser permissions.');
            },
            {
                enableHighAccuracy: true,
                timeout: 5000,
                maximumAge: 0
            }
        );
    } else {
        alert('Geolocation is not supported by your browser.');
    }
}

function onMapClick(e) {
    if (replacingWaypointId !== null) {
        replaceWaypointLocation(replacingWaypointId, e.latlng.lat, e.latlng.lng);
        replacingWaypointId = null;
        map.getContainer().style.cursor = '';
    } else {
        addWaypoint(e.latlng.lat, e.latlng.lng);
    }
}

function addWaypoint(lat, lng) {
    const id = waypoints.length + 1;
    const waypoint = new Waypoint(id, lat, lng);
    waypoints.push(waypoint);
    
    addMarkerToMap(waypoint, id);
    updateTable();
    
    fetchLocationName(waypoint);
}

function addMarkerToMap(waypoint, orderNumber) {
    const customIcon = L.divIcon({
        className: 'custom-marker',
        html: `<div class="waypoint-marker">${orderNumber}</div>`,
        iconSize: [32, 32],
        iconAnchor: [16, 16]
    });
    
    const marker = L.marker([waypoint.lat, waypoint.lng], { icon: customIcon })
        .addTo(map);
    
    marker.waypointId = waypoint.id;
    
    marker.on('click', function() {
        highlightTableRow(waypoint.id);
        updateMarkerPopup(marker, waypoint, orderNumber);
    });
    
    updateMarkerPopup(marker, waypoint, orderNumber);
    
    waypointMarkers.push(marker);
}

function updateMarkerPopup(marker, waypoint, orderNumber) {
    let popupContent = `<strong>Waypoint ${orderNumber}</strong><br>`;
    popupContent += `Lat: ${waypoint.lat}<br>`;
    popupContent += `Lon: ${waypoint.lng}<br>`;
    
    if (waypoint.locationName) {
        popupContent += `<br><strong>${waypoint.locationName}</strong><br>`;
    }
    
    if (waypoint.weather && !waypoint.weather.error) {
        popupContent += `<br>`;
        if (waypoint.weather.iconUrl) {
            popupContent += `<img src="${waypoint.weather.iconUrl}" alt="${waypoint.weather.condition}" class="weather-icon"> `;
        }
        popupContent += `${waypoint.weather.condition}<br>`;
        
        if (waypoint.weather.temperature) {
            popupContent += `Temp: ${waypoint.weather.temperature}°${waypoint.weather.temperatureUnit}<br>`;
        }
        popupContent += `Wind: ${waypoint.weather.windSpeed} ${waypoint.weather.windDirection}<br>`;
        
        if (waypoint.weather.precipitationProbability !== null) {
            popupContent += `Precip: ${waypoint.weather.precipitationProbability}%`;
        }
    }
    
    marker.bindPopup(popupContent);
}

function updateTable() {
    const tbody = document.getElementById('waypoints-tbody');
    tbody.innerHTML = '';
    
    waypoints.forEach((waypoint, index) => {
        const row = tbody.insertRow();
        row.dataset.waypointId = waypoint.id;
        
        const weatherHtml = getWeatherHtml(waypoint);
        
        row.innerHTML = `
            <td>${index + 1}</td>
            <td><input type="date" value="${waypoint.date}" onchange="updateWaypointField(${waypoint.id}, 'date', this.value)"></td>
            <td><input type="time" value="${waypoint.time}" onchange="updateWaypointField(${waypoint.id}, 'time', this.value)"></td>
            <td><input type="text" value="${waypoint.locationName}" placeholder="Enter location name" onchange="updateWaypointField(${waypoint.id}, 'locationName', this.value)"></td>
            ${weatherHtml}
            <td class="actions-cell">
                <button class="action-btn" onclick="centerOnWaypoint(${waypoint.id})" title="Center on waypoint">
                    <span class="action-icon-container" data-icon="icons/crosshair.svg"></span>
                </button>
                <button class="action-btn" onclick="selectNewLocationForWaypoint(${waypoint.id})" title="Select new location on map">
                    <span class="action-icon-container" data-icon="icons/map_pin.svg"></span>
                </button>
                <button class="action-btn" onclick="searchNewLocationForWaypoint(${waypoint.id})" title="Search for new location">
                    <span class="action-icon-container" data-icon="icons/search.svg"></span>
                </button>
                <button class="action-btn delete-action" onclick="deleteWaypoint(${waypoint.id})" title="Delete waypoint">
                    <span class="action-icon-container" data-icon="icons/delete.svg"></span>
                </button>
            </td>
        `;
        
        row.addEventListener('click', function(e) {
            if (e.target.tagName !== 'INPUT' && e.target.tagName !== 'BUTTON') {
                highlightTableRow(waypoint.id);
                const marker = waypointMarkers.find(m => m.waypointId === waypoint.id);
                if (marker) {
                    map.setView(marker.getLatLng(), 13);
                    marker.openPopup();
                }
            }
        });
        
        row.querySelectorAll('.action-icon-container').forEach(container => {
            const iconPath = container.dataset.icon;
            loadSvgIcon(iconPath, container, 'action-icon');
        });
    });
}

function getWeatherHtml(waypoint) {
    if (waypoint.weatherLoading) {
        return `
            <td colspan="4" class="weather-loading">Loading weather...</td>
        `;
    }
    
    if (waypoint.weather && waypoint.weather.error) {
        return `
            <td colspan="4" class="weather-error">${waypoint.weather.error}</td>
        `;
    }
    
    if (waypoint.weather) {
        const tempDisplay = waypoint.weather.temperature ? 
            `${waypoint.weather.temperature}°${waypoint.weather.temperatureUnit}` : 'N/A';
        const windDisplay = `${waypoint.weather.windSpeed} ${waypoint.weather.windDirection}`;
        const precipDisplay = waypoint.weather.precipitationProbability !== null ? 
            `${waypoint.weather.precipitationProbability}%` : '-';
        
        let weatherIcon = '';
        if (waypoint.weather.iconUrl) {
            weatherIcon = `<img src="${waypoint.weather.iconUrl}" alt="${waypoint.weather.condition}" class="weather-icon"> `;
        }
        
        return `
            <td>${weatherIcon}${waypoint.weather.condition}</td>
            <td>${tempDisplay}</td>
            <td>${windDisplay}</td>
            <td>${precipDisplay}</td>
        `;
    }
    
    return `
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
    `;
}

function updateWaypointField(id, field, value) {
    const waypoint = waypoints.find(w => w.id === id);
    if (waypoint) {
        waypoint[field] = value;
        
        if ((field === 'date' || field === 'time') && waypoint.date && waypoint.time) {
            fetchWeatherForWaypoint(waypoint);
        }
    }
}

async function fetchWeatherForWaypoint(waypoint) {
    waypoint.weatherLoading = true;
    waypoint.weather = null;
    updateTable();
    
    try {
        const params = new URLSearchParams({
            latitude: waypoint.lat,
            longitude: waypoint.lng,
            date: waypoint.date,
            time: waypoint.time
        });
        
        const response = await fetch(`/api/weather/forecast?${params}`);
        const data = await response.json();
        
        waypoint.weather = data;
        waypoint.weatherLoading = false;
        updateTable();
        updateMarkerWithWeather(waypoint);
    } catch (error) {
        waypoint.weather = { error: 'Failed to fetch weather data' };
        waypoint.weatherLoading = false;
        updateTable();
    }
}

function updateMarkerWithWeather(waypoint) {
    const index = waypoints.findIndex(w => w.id === waypoint.id);
    if (index !== -1) {
        const marker = waypointMarkers[index];
        if (marker) {
            updateMarkerPopup(marker, waypoint, index + 1);
        }
    }
}

async function fetchLocationName(waypoint) {
    try {
        const params = new URLSearchParams({
            latitude: waypoint.lat,
            longitude: waypoint.lng
        });
        
        const response = await fetch(`/api/location/reverse?${params}`);
        const data = await response.json();
        
        if (data.locationName) {
            waypoint.locationName = data.locationName;
            updateTable();
            updateMarkerWithLocation(waypoint);
        }
    } catch (error) {
        console.warn('Failed to fetch location name:', error);
    }
}

function updateMarkerWithLocation(waypoint) {
    const index = waypoints.findIndex(w => w.id === waypoint.id);
    if (index !== -1) {
        const marker = waypointMarkers[index];
        if (marker) {
            updateMarkerPopup(marker, waypoint, index + 1);
        }
    }
}

function deleteWaypoint(id) {
    const index = waypoints.findIndex(w => w.id === id);
    if (index !== -1) {
        waypoints.splice(index, 1);
        
        const markerIndex = waypointMarkers.findIndex(m => m.waypointId === id);
        if (markerIndex !== -1) {
            map.removeLayer(waypointMarkers[markerIndex]);
            waypointMarkers.splice(markerIndex, 1);
        }
        
        waypointMarkers.forEach(marker => map.removeLayer(marker));
        waypointMarkers = [];
        
        waypoints.forEach((wp, idx) => {
            addMarkerToMap(wp, idx + 1);
        });
        
        updateTable();
    }
}

function highlightTableRow(waypointId) {
    document.querySelectorAll('#waypoints-tbody tr').forEach(row => {
        row.classList.remove('selected');
    });
    
    const row = document.querySelector(`#waypoints-tbody tr[data-waypoint-id="${waypointId}"]`);
    if (row) {
        row.classList.add('selected');
    }
}

function centerOnWaypoint(waypointId) {
    const waypoint = waypoints.find(w => w.id === waypointId);
    if (waypoint) {
        const currentZoom = map.getZoom();
        map.setView([waypoint.lat, waypoint.lng], currentZoom);
        
        const marker = waypointMarkers.find(m => m.waypointId === waypointId);
        if (marker) {
            marker.openPopup();
        }
        
        highlightTableRow(waypointId);
    }
}

function selectNewLocationForWaypoint(waypointId) {
    replacingWaypointId = waypointId;
    map.getContainer().style.cursor = 'crosshair';
    alert('Click on the map to select a new location for this waypoint.');
}

function replaceWaypointLocation(waypointId, newLat, newLng) {
    const waypoint = waypoints.find(w => w.id === waypointId);
    if (waypoint) {
        waypoint.lat = newLat.toFixed(6);
        waypoint.lng = newLng.toFixed(6);
        waypoint.locationName = '';
        waypoint.weather = null;
        
        const index = waypoints.findIndex(w => w.id === waypointId);
        if (index !== -1) {
            const marker = waypointMarkers[index];
            if (marker) {
                marker.setLatLng([newLat, newLng]);
                updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
        
        updateTable();
        fetchLocationName(waypoint);
    }
}

function searchNewLocationForWaypoint(waypointId) {
    replacingWaypointId = waypointId;
    const modal = document.getElementById('search-modal');
    const searchInput = document.getElementById('search-input');
    modal.style.display = 'block';
    searchInput.focus();
}

function getUserLocation() {
    if ("geolocation" in navigator) {
        navigator.geolocation.getCurrentPosition(
            function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;
                initializeMap(lat, lng, USER_ZOOM);
            },
            function(error) {
                console.warn('Geolocation error:', error.message);
                console.log('Using default location (center of USA)');
                initializeMap(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZOOM);
            },
            {
                enableHighAccuracy: true,
                timeout: 5000,
                maximumAge: 0
            }
        );
    } else {
        console.log('Geolocation not supported, using default location');
        initializeMap(DEFAULT_LAT, DEFAULT_LNG, DEFAULT_ZOOM);
    }
}

document.addEventListener('DOMContentLoaded', function() {
    getUserLocation();
    initializeSearch();
});

let searchDebounceTimer = null;

function initializeSearch() {
    const modal = document.getElementById('search-modal');
    const btn = document.getElementById('search-btn');
    const closeBtn = document.querySelector('.close');
    const searchInput = document.getElementById('search-input');
    
    btn.onclick = function() {
        modal.style.display = 'block';
        searchInput.focus();
    };
    
    closeBtn.onclick = function() {
        modal.style.display = 'none';
        searchInput.value = '';
        document.getElementById('search-results').innerHTML = '';
    };
    
    window.onclick = function(event) {
        if (event.target == modal) {
            modal.style.display = 'none';
            searchInput.value = '';
            document.getElementById('search-results').innerHTML = '';
        }
    };
    
    searchInput.addEventListener('input', function() {
        const query = searchInput.value.trim();
        
        if (searchDebounceTimer) {
            clearTimeout(searchDebounceTimer);
        }
        
        if (query.length < 2) {
            document.getElementById('search-results').innerHTML = '';
            return;
        }
        
        document.getElementById('search-results').innerHTML = '<div class="search-loading">Searching...</div>';
        
        searchDebounceTimer = setTimeout(() => {
            performSearch(query);
        }, 1000);
    });
}

async function performSearch(query) {
    try {
        const params = new URLSearchParams({ query: query });
        const response = await fetch(`/api/location/search?${params}`);
        const data = await response.json();
        
        displaySearchResults(data);
    } catch (error) {
        console.error('Search error:', error);
        document.getElementById('search-results').innerHTML = '<div class="search-no-results">Error performing search</div>';
    }
}

function displaySearchResults(data) {
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
        
        const label = properties.label || properties.name || 'Unknown';
        const details = [];
        
        if (properties.locality) details.push(properties.locality);
        if (properties.region) details.push(properties.region);
        if (properties.country) details.push(properties.country);
        
        resultItem.innerHTML = `
            <div class="result-label">${label}</div>
            <div class="result-details">${details.join(', ')}</div>
        `;
        
        resultItem.onclick = function() {
            selectSearchResult(coordinates[1], coordinates[0], label);
        };
        
        resultsContainer.appendChild(resultItem);
    });
}

function selectSearchResult(lat, lng, locationName) {
    document.getElementById('search-modal').style.display = 'none';
    document.getElementById('search-input').value = '';
    document.getElementById('search-results').innerHTML = '';
    
    if (replacingWaypointId !== null) {
        replaceWaypointLocation(replacingWaypointId, lat, lng);
        const waypoint = waypoints.find(w => w.id === replacingWaypointId);
        if (waypoint) {
            waypoint.locationName = locationName;
            updateTable();
            const index = waypoints.findIndex(w => w.id === replacingWaypointId);
            if (index !== -1) {
                const marker = waypointMarkers[index];
                if (marker) {
                    updateMarkerPopup(marker, waypoint, index + 1);
                }
            }
        }
        replacingWaypointId = null;
    } else {
        const id = waypoints.length + 1;
        const waypoint = new Waypoint(id, lat, lng);
        waypoint.locationName = locationName;
        waypoints.push(waypoint);
        
        addMarkerToMap(waypoint, id);
        updateTable();
    }
    
    map.setView([lat, lng], 13);
}
