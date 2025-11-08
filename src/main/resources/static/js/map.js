let map;
let waypoints = [];
let waypointMarkers = [];
let userLocationMarker = null;
let userLocation = { lat: null, lng: null, name: null, timezone: '' };
let replacingWaypointId = null;
let routePolylines = [];
let currentRoute = null;

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
        this.timezone = ''; // timezone abbreviation (MST, MDT, etc.)
        this.duration = 0; // duration in minutes, default to 0
        this.locationName = '';
        this.weather = null;
        this.weatherLoading = false;
    }
}

function getTimezoneAbbr(timezoneId, date = null) {
    // If no timezone provided, return empty
    if (!timezoneId) {
        return '';
    }
    
    try {
        const now = date ? new Date(date) : new Date();
        const formatter = new Intl.DateTimeFormat('en-US', {
            timeZone: timezoneId,
            timeZoneName: 'short'
        });
        
        const parts = formatter.formatToParts(now);
        const timeZoneName = parts.find(part => part.type === 'timeZoneName');
        
        return timeZoneName ? timeZoneName.value : timezoneId.split('/').pop().replace(/_/g, ' ');
    } catch (error) {
        console.warn('Error getting timezone abbreviation:', error);
        return timezoneId.split('/').pop().replace(/_/g, ' ');
    }
}

// ==================== DURATION UTILITY FUNCTIONS ====================

/**
 * Parse duration string like "2h10m", "1.5h", "45m" into minutes
 * @param {string} durationStr - Duration string to parse
 * @returns {number} - Total minutes
 */
function parseDuration(durationStr) {
    if (!durationStr || typeof durationStr !== 'string') {
        return 0;
    }
    
    const trimmed = durationStr.trim().toLowerCase();
    if (!trimmed) {
        return 0;
    }
    
    // Handle pure numbers (treat as minutes)
    if (/^\d+$/.test(trimmed)) {
        return parseInt(trimmed, 10);
    }
    
    let totalMinutes = 0;
    
    // Parse hours (supports decimal hours like "1.5h")
    const hoursMatch = trimmed.match(/(\d+\.?\d*)\s*h/);
    if (hoursMatch) {
        totalMinutes += Math.round(parseFloat(hoursMatch[1]) * 60);
    }
    
    // Parse minutes
    const minutesMatch = trimmed.match(/(\d+)\s*m/);
    if (minutesMatch) {
        totalMinutes += parseInt(minutesMatch[1], 10);
    }
    
    // If no explicit units found, try to parse as minutes
    if (totalMinutes === 0 && /^\d+\.?\d*$/.test(trimmed)) {
        totalMinutes = Math.round(parseFloat(trimmed));
    }
    
    return Math.max(0, totalMinutes);
}

/**
 * Format minutes into duration string with consistent format (e.g., "0h00m", "11h20m", "3d12h00m")
 * @param {number} minutes - Total minutes
 * @returns {string} - Formatted duration string
 */
function formatDuration(minutes) {
    if (!minutes || minutes <= 0) {
        return '0h00m';
    }
    
    const totalMinutes = Math.round(minutes);
    
    // Calculate days, hours, minutes
    const days = Math.floor(totalMinutes / (24 * 60));
    const remainingMinutes = totalMinutes % (24 * 60);
    const hours = Math.floor(remainingMinutes / 60);
    const mins = remainingMinutes % 60;
    
    // Format hours: 1 digit for 0-9 hours, 2 digits for 10+ hours
    const formattedHours = hours.toString();
    
    // Format minutes with 2 digits (always)
    const formattedMinutes = mins.toString().padStart(2, '0');
    
    let result = '';
    
    if (days > 0) {
        result += `${days}d`;
    }
    
    // Always show hours and minutes
    result += `${formattedHours}h${formattedMinutes}m`;
    
    return result;
}

/**
 * Increment duration by specified minutes
 * @param {string} currentDurationStr - Current duration string
 * @param {number} incrementMinutes - Minutes to increment (can be negative)
 * @returns {string} - New duration string
 */
function incrementDuration(currentDurationStr, incrementMinutes) {
    const currentMinutes = parseDuration(currentDurationStr);
    const newMinutes = Math.max(0, currentMinutes + incrementMinutes);
    return formatDuration(newMinutes);
}

/**
 * Validate duration input and return corrected version
 * @param {string} input - User input string
 * @returns {object} - { isValid: boolean, correctedValue: string, minutes: number }
 */
function validateDurationInput(input) {
    if (!input || typeof input !== 'string') {
        return { isValid: true, correctedValue: '', minutes: 0 };
    }
    
    const trimmed = input.trim();
    if (!trimmed) {
        return { isValid: true, correctedValue: '', minutes: 0 };
    }
    
    const minutes = parseDuration(trimmed);
    const formatted = formatDuration(minutes);
    
    // Check if the input was already in correct format
    const isValid = trimmed === formatted || /^\d+$/.test(trimmed);
    
    return {
        isValid: isValid,
        correctedValue: formatted,
        minutes: minutes
    };
}

function initializeMap(lat, lng, zoom) {
    map = L.map('map').setView([lat, lng], zoom);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
    }).addTo(map);

    userLocation.lat = lat.toFixed(6);
    userLocation.lng = lng.toFixed(6);
    
    userLocationMarker = L.marker([lat, lng]).addTo(map);
    updateUserLocationPopup();
    userLocationMarker.openPopup();
    
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
        showLocationLoading();
        
        navigator.geolocation.getCurrentPosition(
            function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;
                const currentZoom = map.getZoom();
                
                userLocation.lat = lat.toFixed(6);
                userLocation.lng = lng.toFixed(6);
                
                map.setView([lat, lng], currentZoom);
                
                if (userLocationMarker) {
                    userLocationMarker.setLatLng([lat, lng]);
                    updateUserLocationPopup();
                    userLocationMarker.openPopup();
                } else {
                    userLocationMarker = L.marker([lat, lng]).addTo(map);
                    updateUserLocationPopup();
                    userLocationMarker.openPopup();
                }
                
                fetchUserLocationName().finally(() => {
                    hideLocationLoading();
                });
            },
            function(error) {
                console.warn('Geolocation error:', error.message);
                hideLocationLoading();
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

function showLocationLoading() {
    const overlay = document.getElementById('location-loading-overlay');
    if (overlay) {
        overlay.classList.add('active');
    }
}

function hideLocationLoading() {
    const overlay = document.getElementById('location-loading-overlay');
    if (overlay) {
        overlay.classList.remove('active');
    }
}

function updateUserLocationPopup() {
    if (!userLocationMarker) return;
    
    let popupContent = '<strong>Your Location</strong><br>';
    popupContent += `Lat: ${userLocation.lat}<br>`;
    popupContent += `Lon: ${userLocation.lng}`;
    
    if (userLocation.name) {
        popupContent += `<br><br><strong>${userLocation.name}</strong>`;
    }
    
    if (userLocation.timezone) {
        popupContent += `<br>Timezone: ${userLocation.timezone}`;
    }
    
    userLocationMarker.bindPopup(popupContent);
}

async function fetchUserLocationName() {
    try {
        const params = new URLSearchParams({
            latitude: userLocation.lat,
            longitude: userLocation.lng
        });
        
        const response = await fetch(`/api/location/reverse?${params}`);
        const data = await response.json();
        
        if (data.locationName) {
            userLocation.name = data.locationName;
        }
        
        // Set timezone from the same response - use standard timezone abbreviation
        if (data.zoneStandard) {
            userLocation.timezone = data.zoneStandard;
        }
        
        updateUserLocationPopup();
    } catch (error) {
        console.warn('Failed to fetch user location name:', error);
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
    updateRouteButtonState();
    clearRouteOnWaypointChange('add', waypoints.length - 1);
    
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
    
    if (waypoint.timezone) {
        popupContent += `Timezone: ${waypoint.timezone}<br>`;
    }
    
    if (waypoint.duration > 0) {
        const totalMinutes = Math.round(waypoint.duration);
        const days = Math.floor(totalMinutes / (24 * 60));
        const remainingMinutes = totalMinutes % (24 * 60);
        const hours = Math.floor(remainingMinutes / 60);
        const mins = remainingMinutes % 60;
        
        let durationText = 'Duration: ';
        if (days > 0) {
            durationText += `${days} days, `;
        }
        if (hours > 0 || days > 0) {
            durationText += `${hours} hours, `;
        }
        durationText += `${mins} minutes`;
        
        popupContent += `${durationText}<br>`;
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
            <td class="drag-handle-cell"><span class="drag-handle" title="Drag to reorder">☰</span></td>
            <td>${index + 1}</td>
            <td><input type="date" value="${waypoint.date}" onchange="updateWaypointField(${waypoint.id}, 'date', this.value)"></td>
            <td><input type="time" value="${waypoint.time}" onchange="updateWaypointField(${waypoint.id}, 'time', this.value)"></td>
            <td>${waypoint.timezone || '-'}</td>
            <td>
                <div class="duration-input-container">
                    <input type="text" 
                           value="${formatDuration(waypoint.duration)}" 
                           placeholder="2h10m" 
                           onblur="validateAndUpdateDuration(${waypoint.id}, this.value)"
                           onkeydown="handleDurationKeydown(event, ${waypoint.id}, this.value)"
                           class="duration-input"
                           title="Enter duration like 2h10m, 1.5h, or 45m">
                    <div class="duration-arrows">
                        <button class="duration-arrow-up" onclick="incrementDurationValue(${waypoint.id}, 10)" title="Add 10 minutes">▲</button>
                        <button class="duration-arrow-down" onclick="incrementDurationValue(${waypoint.id}, -10)" title="Subtract 10 minutes">▼</button>
                    </div>
                </div>
            </td>
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
        
        const dragHandle = row.querySelector('.drag-handle');
        dragHandle.draggable = true;
        
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
        
        setupDragAndDrop(row);
    });
    
    // Add a drop zone row at the bottom for dragging to the last position
    if (waypoints.length > 0) {
        const dropZoneRow = tbody.insertRow();
        dropZoneRow.className = 'drop-zone-row';
        dropZoneRow.innerHTML = `
            <td colspan="12" class="drop-zone-cell"></td>
        `;
        setupDropZone(dropZoneRow);
    }
}

let draggedRow = null;

function setupDragAndDrop(row) {
    const dragHandle = row.querySelector('.drag-handle');
    
    dragHandle.addEventListener('dragstart', function(e) {
        draggedRow = row;
        row.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', row.dataset.waypointId);
    });
    
    dragHandle.addEventListener('dragend', function(e) {
        row.classList.remove('dragging');
        document.querySelectorAll('#waypoints-tbody tr').forEach(r => {
            r.classList.remove('drag-over');
        });
        draggedRow = null;
    });
    
    row.addEventListener('dragover', function(e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        
        if (draggedRow && draggedRow !== row) {
            row.classList.add('drag-over');
        }
        return false;
    });
    
    row.addEventListener('dragleave', function(e) {
        row.classList.remove('drag-over');
    });
    
    row.addEventListener('drop', function(e) {
        e.preventDefault();
        e.stopPropagation();
        
        if (draggedRow && draggedRow !== row) {
            const draggedId = parseInt(draggedRow.dataset.waypointId);
            const targetId = parseInt(row.dataset.waypointId);
            
            reorderWaypoints(draggedId, targetId);
        }
        
        return false;
    });
}

function setupDropZone(dropZoneRow) {
    dropZoneRow.addEventListener('dragover', function(e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        
        if (draggedRow) {
            dropZoneRow.classList.add('drag-over');
        }
        return false;
    });
    
    dropZoneRow.addEventListener('dragleave', function(e) {
        dropZoneRow.classList.remove('drag-over');
    });
    
    dropZoneRow.addEventListener('drop', function(e) {
        e.preventDefault();
        e.stopPropagation();
        
        if (draggedRow) {
            const draggedId = parseInt(draggedRow.dataset.waypointId);
            moveToEnd(draggedId);
        }
        
        return false;
    });
}

function moveToEnd(draggedId) {
    const draggedIndex = waypoints.findIndex(w => w.id === draggedId);
    
    if (draggedIndex === -1) return;
    
    // Don't do anything if already at the end
    if (draggedIndex === waypoints.length - 1) return;
    
    const [removed] = waypoints.splice(draggedIndex, 1);
    waypoints.push(removed);
    
    updateMarkersAfterReorder();
    updateTable();
    clearRouteOnWaypointChange('reorder');
}

function reorderWaypoints(draggedId, targetId) {
    const draggedIndex = waypoints.findIndex(w => w.id === draggedId);
    const targetIndex = waypoints.findIndex(w => w.id === targetId);
    
    if (draggedIndex === -1 || targetIndex === -1) return;
    
    // Don't do anything if dragging to the same position
    if (draggedIndex === targetIndex) return;
    
    const [removed] = waypoints.splice(draggedIndex, 1);
    
    // If we removed an item before the target, the target index shifts left by 1
    const adjustedTargetIndex = draggedIndex < targetIndex ? targetIndex - 1 : targetIndex;
    
    waypoints.splice(adjustedTargetIndex, 0, removed);
    
    updateMarkersAfterReorder();
    updateTable();
    clearRouteOnWaypointChange('reorder');
}

function updateMarkersAfterReorder() {
    waypointMarkers.forEach(marker => map.removeLayer(marker));
    waypointMarkers = [];
    
    waypoints.forEach((waypoint, index) => {
        addMarkerToMap(waypoint, index + 1);
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

/**
 * Validate and update duration field
 * @param {number} waypointId - ID of the waypoint
 * @param {string} inputValue - User input value
 */
function validateAndUpdateDuration(waypointId, inputValue) {
    const waypoint = waypoints.find(w => w.id === waypointId);
    if (!waypoint) return;
    
    const validation = validateDurationInput(inputValue);
    
    // Update the input field with corrected value if needed
    const inputElement = document.querySelector(`tr[data-waypoint-id="${waypointId}"] .duration-input`);
    if (inputElement && !validation.isValid) {
        inputElement.value = validation.correctedValue;
    }
    
    // Update waypoint duration (convert to minutes for backend)
    waypoint.duration = validation.minutes;
    
    // Update marker popup to show new duration
    const marker = waypointMarkers.find(m => m.waypointId === waypointId);
    if (marker) {
        const index = waypoints.findIndex(w => w.id === waypointId);
        updateMarkerPopup(marker, waypoint, index + 1);
    }
}

/**
 * Handle keyboard input for duration field
 * @param {Event} event - Keyboard event
 * @param {number} waypointId - ID of the waypoint
 * @param {string} currentValue - Current input value
 */
function handleDurationKeydown(event, waypointId, currentValue) {
    if (event.key === 'Enter') {
        event.preventDefault();
        validateAndUpdateDuration(waypointId, currentValue);
        event.target.blur();
    } else if (event.key === 'Escape') {
        event.preventDefault();
        // Revert to current waypoint value
        const waypoint = waypoints.find(w => w.id === waypointId);
        if (waypoint) {
            event.target.value = formatDuration(waypoint.duration);
        }
        event.target.blur();
    }
}

/**
 * Increment duration value using arrow buttons
 * @param {number} waypointId - ID of the waypoint
 * @param {number} incrementMinutes - Minutes to increment (can be negative)
 */
function incrementDurationValue(waypointId, incrementMinutes) {
    const waypoint = waypoints.find(w => w.id === waypointId);
    if (!waypoint) return;
    
    const currentFormatted = formatDuration(waypoint.duration);
    const newFormatted = incrementDuration(currentFormatted, incrementMinutes);
    const newMinutes = parseDuration(newFormatted);
    
    // Update waypoint
    waypoint.duration = newMinutes;
    
    // Update input field
    const inputElement = document.querySelector(`tr[data-waypoint-id="${waypointId}"] .duration-input`);
    if (inputElement) {
        inputElement.value = newFormatted;
    }
    
    // Update marker popup
    const marker = waypointMarkers.find(m => m.waypointId === waypointId);
    if (marker) {
        const index = waypoints.findIndex(w => w.id === waypointId);
        updateMarkerPopup(marker, waypoint, index + 1);
    }
}

function updateWaypointField(id, field, value) {
    const waypoint = waypoints.find(w => w.id === id);
    if (waypoint) {
        if (field === 'duration') {
            // This is now handled by validateAndUpdateDuration
            return;
        } else {
            waypoint[field] = value;
        }
        
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
        }
        
        // Set timezone from the same response - use standard timezone abbreviation
        if (data.zoneStandard) {
            waypoint.timezone = data.zoneStandard;
        }
        
        updateTable();
        updateMarkerWithLocation(waypoint);
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
        updateRouteButtonState();
        clearRouteOnWaypointChange('delete');
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
        waypoint.timezone = '';
        
        const index = waypoints.findIndex(w => w.id === waypointId);
        if (index !== -1) {
            const marker = waypointMarkers[index];
            if (marker) {
                marker.setLatLng([newLat, newLng]);
                updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
        
        updateTable();
        clearRouteOnWaypointChange('modify');
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
        showLocationLoading();
        
        navigator.geolocation.getCurrentPosition(
            function(position) {
                const lat = position.coords.latitude;
                const lng = position.coords.longitude;
                initializeMap(lat, lng, USER_ZOOM);
                
                fetchUserLocationName().finally(() => {
                    hideLocationLoading();
                });
            },
            function(error) {
                console.warn('Geolocation error:', error.message);
                console.log('Using default location (center of USA)');
                hideLocationLoading();
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
    initializeRouting();
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
        updateRouteButtonState();
        clearRouteOnWaypointChange('add', waypoints.length - 1);
    }
    
    map.setView([lat, lng], 13);
}

// ==================== ROUTING FUNCTIONS ====================

function initializeRouting() {
    const calculateRouteBtn = document.getElementById('calculate-route-btn');
    if (calculateRouteBtn) {
        calculateRouteBtn.addEventListener('click', calculateRoute);
    }
    
    updateRouteButtonState();
}

function showRouteLoading() {
    const overlay = document.getElementById('route-loading-overlay');
    if (overlay) {
        overlay.classList.add('active');
    }
}

function hideRouteLoading() {
    const overlay = document.getElementById('route-loading-overlay');
    if (overlay) {
        overlay.classList.remove('active');
    }
}

function updateRouteButtonState() {
    const calculateRouteBtn = document.getElementById('calculate-route-btn');
    if (calculateRouteBtn) {
        if (waypoints.length < 2) {
            calculateRouteBtn.disabled = true;
            calculateRouteBtn.title = 'Add at least 2 waypoints to calculate route';
        } else {
            calculateRouteBtn.disabled = false;
            calculateRouteBtn.title = 'Calculate optimal route between waypoints';
        }
    }
}

async function calculateRoute() {
    if (waypoints.length < 2) {
        alert('Please add at least 2 waypoints to calculate a route.');
        return;
    }

    showRouteLoading();

    try {
        // Prepare waypoints for API request with date, time, and duration
        const waypointData = waypoints.map(wp => ({
            latitude: parseFloat(wp.lat),
            longitude: parseFloat(wp.lng),
            name: wp.locationName || `Waypoint ${wp.id}`,
            date: wp.date || '',
            time: wp.time || '',
            duration: wp.duration || 0
        }));

        const response = await fetch('/api/route/calculate', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(waypointData)
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const routeData = await response.json();

        if (routeData && routeData.geometry && routeData.geometry.length > 0) {
            displayRoute(routeData);
            currentRoute = routeData;
        } else {
            throw new Error('No route geometry returned from API');
        }

    } catch (error) {
        console.error('Route calculation error:', error);
        alert('Failed to calculate route: ' + error.message);
        clearRoute();
    } finally {
        hideRouteLoading();
    }
}

function displayRoute(routeData) {
    // Clear existing route
    clearRoute();

    if (!routeData.geometry || routeData.geometry.length === 0) {
        console.warn('No route geometry to display');
        return;
    }

    // Convert geometry coordinates for Leaflet (Leaflet expects [lat, lng])
    const routeCoordinates = routeData.geometry.map(coord => [coord[1], coord[0]]);

    // Create polyline for the route - changed color from green (#27ae60) to blue (#0066cc)
    const routePolyline = L.polyline(routeCoordinates, {
        color: '#0066cc',
        weight: 4,
        opacity: 0.8,
        smoothFactor: 1
    }).addTo(map);

    routePolylines.push(routePolyline);

    // Fit map to show the entire route
    const bounds = L.latLngBounds(routeCoordinates);
    map.fitBounds(bounds, { padding: [50, 50] });

    // Update waypoint arrival times, durations, and timezones if provided
    if (routeData.waypoints && routeData.waypoints.length > 0) {
        updateWaypointWithRouteData(routeData.waypoints);
    }

    // Log route information
    console.log('Route calculated:', {
        distance: routeData.distance ? (routeData.distance / 1000).toFixed(1) + ' km' : 'Unknown',
        duration: routeData.duration ? formatDuration(routeData.duration) : 'Unknown',
        waypoints: routeData.waypoints ? routeData.waypoints.length : 0
    });

    // Update button text to show route is active
    const calculateRouteBtn = document.getElementById('calculate-route-btn');
    if (calculateRouteBtn) {
        calculateRouteBtn.textContent = '🔄 Recalculate Route';
    }
}

function updateWaypointWithRouteData(routeWaypoints) {
    routeWaypoints.forEach((routeWaypoint, index) => {
        if (index < waypoints.length) {
            const waypoint = waypoints[index];
            
            // Update arrival time if provided by the route calculation
            if (routeWaypoint.arrivalTime) {
                const arrivalDateTime = routeWaypoint.arrivalTime.split(' ');
                if (arrivalDateTime.length === 2) {
                    waypoint.date = arrivalDateTime[0];
                    waypoint.time = arrivalDateTime[1];
                    
                    // Fetch weather for the updated arrival time
                    if (waypoint.date && waypoint.time) {
                        fetchWeatherForWaypoint(waypoint);
                    }
                }
            }
            
            // Update duration if provided
            if (routeWaypoint.duration !== undefined && routeWaypoint.duration !== null) {
                waypoint.duration = routeWaypoint.duration;
            }
            
            // Store timezone information
            if (routeWaypoint.timezone) {
                waypoint.timezone = getTimezoneAbbr(routeWaypoint.timezone, waypoint.date && waypoint.time ? `${waypoint.date} ${waypoint.time}` : null);
            }
        }
    });
    
    // Update the table to show the new arrival times, durations, and timezones
    updateTable();
}

function clearRoute() {
    // Remove all route polylines from the map
    routePolylines.forEach(polyline => {
        map.removeLayer(polyline);
    });
    routePolylines = [];
    currentRoute = null;

    // Reset button text
    const calculateRouteBtn = document.getElementById('calculate-route-btn');
    if (calculateRouteBtn) {
        calculateRouteBtn.textContent = '🛣️ Calculate Route';
    }
}


// Clear route when waypoints are reordered or deleted, but not when adding waypoints at the end
function clearRouteOnWaypointChange(changeType, waypointIndex = null) {
    if (!currentRoute) {
        return;
    }
    
    let shouldClearRoute = false;
    
    switch (changeType) {
        case 'delete':
            // Any deletion affects the route
            shouldClearRoute = true;
            break;
            
        case 'reorder':
            // Any reordering affects the route
            shouldClearRoute = true;
            break;
            
        case 'add':
            // Only clear if adding at a position that affects existing waypoints
            if (waypointIndex !== null && waypointIndex < waypoints.length - 1) {
                shouldClearRoute = true;
            }
            break;
            
        case 'modify':
            // Any modification to existing waypoint affects the route
            shouldClearRoute = true;
            break;
    }
    
    if (shouldClearRoute) {
        clearRoute();
        console.log('Route cleared due to waypoint changes');
    }
}

// Helper function to determine if a change affects existing route
function changeAffectsExistingRoute(changeType, waypointIndex) {
    if (!currentRoute || waypointIndex === null) {
        return true;
    }
    
    // For additions, only affects route if not at the end
    if (changeType === 'add') {
        return waypointIndex < currentRoute.getWaypoints().length;
    }
    
    // For all other changes, it affects the route
    return true;
}
