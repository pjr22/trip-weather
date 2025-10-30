let map;
let waypoints = [];
let waypointMarkers = [];
let userLocationMarker = null;

const DEFAULT_LAT = 39.8283;
const DEFAULT_LNG = -98.5795;
const DEFAULT_ZOOM = 4;
const USER_ZOOM = 13;

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
}

function onMapClick(e) {
    addWaypoint(e.latlng.lat, e.latlng.lng);
}

function addWaypoint(lat, lng) {
    const id = waypoints.length + 1;
    const waypoint = new Waypoint(id, lat, lng);
    waypoints.push(waypoint);
    
    addMarkerToMap(waypoint, id);
    updateTable();
}

function addMarkerToMap(waypoint, orderNumber) {
    const customIcon = L.divIcon({
        className: 'custom-marker',
        html: `<div class="waypoint-marker">${orderNumber}</div>`,
        iconSize: [32, 32],
        iconAnchor: [16, 16]
    });
    
    const marker = L.marker([waypoint.lat, waypoint.lng], { icon: customIcon })
        .addTo(map)
        .bindPopup(`Waypoint ${orderNumber}<br>Lat: ${waypoint.lat}<br>Lng: ${waypoint.lng}`);
    
    marker.waypointId = waypoint.id;
    
    marker.on('click', function() {
        highlightTableRow(waypoint.id);
    });
    
    waypointMarkers.push(marker);
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
            <td>${waypoint.lat}</td>
            <td>${waypoint.lng}</td>
            ${weatherHtml}
            <td><button class="delete-btn" onclick="deleteWaypoint(${waypoint.id})">Delete</button></td>
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
    });
}

function getWeatherHtml(waypoint) {
    if (waypoint.weatherLoading) {
        return `
            <td colspan="3" class="weather-loading">Loading weather...</td>
        `;
    }
    
    if (waypoint.weather && waypoint.weather.error) {
        return `
            <td colspan="3" class="weather-error">${waypoint.weather.error}</td>
        `;
    }
    
    if (waypoint.weather) {
        const tempDisplay = waypoint.weather.temperature ? 
            `${waypoint.weather.temperature}°${waypoint.weather.temperatureUnit}` : 'N/A';
        const windDisplay = `${waypoint.weather.windSpeed} ${waypoint.weather.windDirection}`;
        
        return `
            <td>${waypoint.weather.condition}</td>
            <td>${tempDisplay}</td>
            <td>${windDisplay}</td>
        `;
    }
    
    return `
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
    } catch (error) {
        waypoint.weather = { error: 'Failed to fetch weather data' };
        waypoint.weatherLoading = false;
        updateTable();
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
});
