/**
 * Waypoint Renderer
 * Handles rendering of waypoints in table and on map
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.WaypointRenderer = {
    
    /**
     * Format date and time for waypoint popup
     * @param {object} waypoint - Waypoint object
     * @param {boolean} isDeparture - Whether this is departure time (adds duration)
     * @returns {string} - Formatted date/time string
     */
    formatWaypointTime: function(waypoint, isDeparture = false) {
        if (!waypoint.date || !waypoint.time) {
            return '';
        }
        
        try {
            // Parse the date and time
            const dateTimeStr = `${waypoint.date} ${waypoint.time}`;
            let date = new Date(dateTimeStr);
            
            // Add duration if this is departure time
            if (isDeparture && waypoint.duration > 0) {
                date = new Date(date.getTime() + (waypoint.duration * 60 * 1000));
            }
            
            // Format date as MM/DD/YY
            const month = (date.getMonth() + 1).toString().padStart(2, '0');
            const day = date.getDate().toString().padStart(2, '0');
            const year = date.getFullYear().toString().slice(-2);
            const dateStr = `${month}/${day}/${year}`;
            
            // Format time as HH:MM AM/PM
            let hours = date.getHours();
            const minutes = date.getMinutes().toString().padStart(2, '0');
            const ampm = hours >= 12 ? 'PM' : 'AM';
            hours = hours % 12 || 12; // Convert to 12-hour format, 0 becomes 12
            const timeStr = `${hours.toString().padStart(2, '0')}:${minutes} ${ampm}`;
            
            // Get timezone abbreviation from stored data
            const timezoneAbbr = window.TripWeather.Utils.Timezone.getTimezoneAbbrFromWaypoint(waypoint, date);
            
            return `${dateStr} ${timeStr} ${timezoneAbbr}`;
        } catch (error) {
            console.warn('Error formatting waypoint time:', error);
            return '';
        }
    },
    
    /**
     * Add marker to map for waypoint
     * @param {object} waypoint - Waypoint object
     * @param {number} orderNumber - Order number for display
     * @returns {L.Marker} - Created marker
     */
    addMarkerToMap: function(waypoint, orderNumber) {
        const customIcon = L.divIcon({
            className: 'custom-marker',
            html: `<div class="waypoint-marker">${orderNumber}</div>`,
            iconSize: [32, 32],
            iconAnchor: [16, 16]
        });
        
        const map = window.TripWeather.Managers.Map.getMap();
        const marker = L.marker([waypoint.lat, waypoint.lng], { icon: customIcon })
            .addTo(map);
        
        marker.waypointSequence = waypoint.sequence;
        
        marker.on('click', function() {
            this.highlightTableRow(waypoint.sequence);
            this.updateMarkerPopup(marker, waypoint, orderNumber);
        }.bind(this));
        
        this.updateMarkerPopup(marker, waypoint, orderNumber);
        
        // Add to waypoint manager's markers array
        window.TripWeather.Managers.Waypoint.waypointMarkers.push(marker);
        
        return marker;
    },

    /**
     * Update marker popup content
     * @param {L.Marker} marker - Marker to update
     * @param {object} waypoint - Waypoint object
     * @param {number} orderNumber - Order number
     */
    updateMarkerPopup: function(marker, waypoint, orderNumber) {
        const lastWaypointNumber = window.TripWeather.Managers.Waypoint.getLastWaypointNumber();
        const isStart = orderNumber === 1;
        const isEnd = orderNumber === lastWaypointNumber;
        let waypointLabel = isStart ? 'Start' : isEnd ? 'End' : `Waypoint ${orderNumber}`;
        let popupContent = `<strong>${waypointLabel}</strong><br>`;
        popupContent += `Lat: ${waypoint.lat}<br>`;
        popupContent += `Lon: ${waypoint.lng}<br>`;
        
        if (waypoint.locationName) {
            popupContent += `<br><strong>${waypoint.locationName}</strong><br>`;
            
            // Add timezone name after location name
            if (waypoint.timezoneName) {
                popupContent += `<small>Timezone: ${waypoint.timezoneName}</small><br>`;
            }
        }
        
        // Add Arrival Time
        const arrivalTime = this.formatWaypointTime(waypoint, false);
        if (!isStart && arrivalTime) {
            popupContent += `Arrival Time: ${arrivalTime}<br>`;
        }
        
        // Add Departure Time if there's a duration
        if ((!isEnd && waypoint.duration > 0) || (isStart && arrivalTime)) {
            const totalMinutes = Math.round(waypoint.duration);
            const days = Math.floor(totalMinutes / (24 * 60));
            const remainingMinutes = totalMinutes % (24 * 60);
            const hours = Math.floor(remainingMinutes / 60);
            const mins = remainingMinutes % 60;

            // Add Departure Time
            const departureTime = this.formatWaypointTime(waypoint, true);
            if (departureTime) {
                popupContent += `Departure Time: ${departureTime}<br>`;
            }

            // Add Duration
            let durationText = 'Time spent here: ';
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
            popupContent += window.TripWeather.Services.Weather.generateWeatherPopupHtml(waypoint.weather);
        }
        
        marker.bindPopup(popupContent);
    },

    /**
     * Update waypoints table
     */
    updateTable: function() {
        const tbody = document.getElementById('waypoints-tbody');
        if (!tbody) return;
        
        tbody.innerHTML = '';
        
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        
        waypoints.forEach((waypoint, index) => {
            const row = tbody.insertRow();
            row.dataset.waypointSequence = waypoint.sequence;
            
            const weatherHtml = window.TripWeather.Services.Weather.generateWeatherHtml(waypoint.weather, waypoint.weatherLoading);
            
            // Get the appropriate timezone abbreviation based on date/time
            let timezoneDisplay = '-';
            if (waypoint.timezoneName) {
                if (waypoint.date && waypoint.time) {
                    // If we have date and time, determine DST status
                    const dateTimeStr = `${waypoint.date} ${waypoint.time}`;
                    const date = new Date(dateTimeStr);
                    timezoneDisplay = window.TripWeather.Utils.Timezone.getTimezoneAbbrFromWaypoint(waypoint, date);
                } else {
                    // Default to standard time if no date/time
                    timezoneDisplay = waypoint.timezoneStdAbbr || '-';
                }
            }
            
            row.innerHTML = `
                <td class="drag-handle-cell"><span class="drag-handle" title="Drag to reorder">☰</span></td>
                <td>${index + 1}</td>
                <td><input type="date" value="${waypoint.date}" data-waypoint-sequence="${waypoint.sequence}" data-field="date"></td>
                <td><input type="time" value="${waypoint.time}" data-waypoint-sequence="${waypoint.sequence}" data-field="time"></td>
                <td>${timezoneDisplay}</td>
                <td>
                    <div class="duration-input-container">
                        <input type="text"
                               value="${window.TripWeather.Utils.Duration.formatDuration(waypoint.duration)}"
                               placeholder="3d2h10m"
                               data-waypoint-sequence="${waypoint.sequence}"
                               data-field="duration"
                               class="duration-input"
                               title="Enter duration like 3d2h10m, 48h22m, 1000m, 1.5h">
                        <div class="duration-arrows">
                            <button class="duration-arrow-up" data-waypoint-sequence="${waypoint.sequence}" data-increment="10" title="Add 10 minutes">▲</button>
                            <button class="duration-arrow-down" data-waypoint-sequence="${waypoint.sequence}" data-increment="-10" title="Subtract 10 minutes">▼</button>
                        </div>
                    </div>
                </td>
                <td><input type="text" value="${waypoint.locationName}" placeholder="Enter location name" data-waypoint-sequence="${waypoint.sequence}" data-field="locationName"></td>
                ${weatherHtml}
                <td class="actions-cell">
                    <button class="action-btn" data-waypoint-sequence="${waypoint.sequence}" data-action="center" title="Center on waypoint">
                        <span class="action-icon-container" data-icon="icons/crosshair.svg"></span>
                    </button>
                    <button class="action-btn" data-waypoint-sequence="${waypoint.sequence}" data-action="select-location" title="Select new location on map">
                        <span class="action-icon-container" data-icon="icons/map_pin.svg"></span>
                    </button>
                    <button class="action-btn" data-waypoint-sequence="${waypoint.sequence}" data-action="search-location" title="Search for new location">
                        <span class="action-icon-container" data-icon="icons/search.svg"></span>
                    </button>
                    <button class="action-btn delete-action" data-waypoint-sequence="${waypoint.sequence}" data-action="delete" title="Delete waypoint">
                        <span class="action-icon-container" data-icon="icons/delete.svg"></span>
                    </button>
                </td>
            `;
            
            // Initialize drag and drop for the row
            this.setupDragAndDrop(row);
            
            // Setup event handlers using data attributes instead of inline onclick
            this.setupRowEventHandlers(row, waypoint, index);
            
            // Load action icons
            row.querySelectorAll('.action-icon-container').forEach(container => {
                const iconPath = container.dataset.icon;
                window.TripWeather.Utils.IconLoader.loadSvgIcon(iconPath, container, 'action-icon');
            });
        });
        
        // Add drop zone row at the bottom for dragging to the last position
        if (waypoints.length > 0) {
            const dropZoneRow = tbody.insertRow();
            dropZoneRow.className = 'drop-zone-row';
            dropZoneRow.innerHTML = `
                <td colspan="12" class="drop-zone-cell"></td>
            `;
            this.setupDropZone(dropZoneRow);
        }
    },

    /**
     * Setup event handlers for a table row using data attributes
     * @param {HTMLTableRowElement} row - Table row element
     * @param {object} waypoint - Waypoint object
     * @param {number} index - Index in waypoints array
     */
    setupRowEventHandlers: function(row, waypoint, index) {
        // Setup input change handlers
        row.querySelectorAll('input[data-waypoint-sequence]').forEach(input => {
            const sequence = parseInt(input.dataset.waypointSequence);
            const field = input.dataset.field;
            
            if (field === 'duration') {
                input.addEventListener('blur', function(e) {
                    window.TripWeather.Managers.Waypoint.updateWaypointDuration(sequence, e.target.value);
                });
                
                input.addEventListener('keydown', function(e) {
                    this.handleDurationKeydown(e, sequence, e.target.value);
                }.bind(this));
            } else {
                input.addEventListener('change', function(e) {
                    window.TripWeather.Managers.Waypoint.updateWaypointField(sequence, field, e.target.value);
                });
            }
        });
        
        // Setup button click handlers
        row.querySelectorAll('button[data-waypoint-sequence]').forEach(button => {
            const sequence = parseInt(button.dataset.waypointSequence);
            const action = button.dataset.action;
            
            if (action === 'center') {
                button.addEventListener('click', function() {
                    window.TripWeather.Managers.Waypoint.centerOnWaypoint(sequence);
                });
            } else if (action === 'select-location') {
                button.addEventListener('click', function() {
                    window.TripWeather.Managers.Waypoint.selectNewLocationForWaypoint(sequence);
                });
            } else if (action === 'search-location') {
                button.addEventListener('click', function() {
                    window.TripWeather.Managers.Search.searchNewLocationForWaypoint(sequence);
                });
            } else if (action === 'delete') {
                button.addEventListener('click', function() {
                    window.TripWeather.Managers.Waypoint.deleteWaypoint(sequence);
                });
            } else if (button.dataset.increment !== undefined) {
                const increment = parseInt(button.dataset.increment);
                button.addEventListener('click', function() {
                    window.TripWeather.Managers.Waypoint.incrementWaypointDuration(sequence, increment);
                });
            }
        });
        
        // Setup row click handler
        row.addEventListener('click', function(e) {
            if (e.target.tagName !== 'INPUT' && e.target.tagName !== 'BUTTON') {
                this.highlightTableRow(waypoint.sequence);
                const marker = window.TripWeather.Managers.Waypoint.waypointMarkers.find(m => m.waypointSequence === waypoint.sequence);
                if (marker) {
                    const map = window.TripWeather.Managers.Map.getMap();
                    if (map) {
                        map.setView(marker.getLatLng(), 13);
                    }
                    marker.openPopup();
                }
            }
        }.bind(this));
    },

    /**
     * Handle keyboard input for duration field
     * @param {Event} event - Keyboard event
     * @param {number} sequence - Waypoint sequence
     * @param {string} currentValue - Current input value
     */
    handleDurationKeydown: function(event, sequence, currentValue) {
        if (event.key === 'Enter') {
            event.preventDefault();
            window.TripWeather.Managers.Waypoint.updateWaypointDuration(sequence, currentValue);
            event.target.blur();
        } else if (event.key === 'Escape') {
            event.preventDefault();
            // Revert to current waypoint value
            const waypoint = window.TripWeather.Managers.Waypoint.getWaypoint(sequence);
            if (waypoint) {
                event.target.value = window.TripWeather.Utils.Duration.formatDuration(waypoint.duration);
            }
            event.target.blur();
        }
    },

    /**
     * Highlight table row for waypoint
     * @param {number} sequence - Waypoint sequence
     */
    highlightTableRow: function(sequence) {
        document.querySelectorAll('#waypoints-tbody tr').forEach(row => {
            row.classList.remove('selected');
        });
        
        const row = document.querySelector(`#waypoints-tbody tr[data-waypoint-sequence="${sequence}"]`);
        if (row) {
            row.classList.add('selected');
        }
    },

    /**
     * Update marker with weather information
     * @param {object} waypoint - Waypoint object
     */
    updateMarkerWithWeather: function(waypoint) {
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.sequence === waypoint.sequence);
        if (index !== -1) {
            const marker = window.TripWeather.Managers.Waypoint.waypointMarkers[index];
            if (marker) {
                this.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
    },

    /**
     * Update marker with location information
     * @param {object} waypoint - Waypoint object
     */
    updateMarkerWithLocation: function(waypoint) {
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.sequence === waypoint.sequence);
        if (index !== -1) {
            const marker = window.TripWeather.Managers.Waypoint.waypointMarkers[index];
            if (marker) {
                this.updateMarkerPopup(marker, waypoint, index + 1);
            }
        }
    },

    /**
     * Setup drag and drop for a table row
     * @param {HTMLTableRowElement} row - Table row element
     */
    setupDragAndDrop: function(row) {
        const dragHandle = row.querySelector('.drag-handle');
        
        // Make the drag handle draggable
        if (dragHandle) {
            dragHandle.draggable = true;
        }
        
        // Drag start
        dragHandle.addEventListener('dragstart', function(e) {
            window.TripWeather.Managers.WaypointRenderer.draggedRow = row;
            row.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData('text/plain', row.dataset.waypointSequence);
            console.log('Drag started for waypoint:', row.dataset.waypointSequence);
        });
        
        // Drag end
        dragHandle.addEventListener('dragend', function(e) {
            row.classList.remove('dragging');
            document.querySelectorAll('#waypoints-tbody tr').forEach(r => {
                r.classList.remove('drag-over');
            });
            window.TripWeather.Managers.WaypointRenderer.draggedRow = null;
            console.log('Drag ended');
        });
        
        // Drag over
        row.addEventListener('dragover', function(e) {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            
            if (window.TripWeather.Managers.WaypointRenderer.draggedRow && window.TripWeather.Managers.WaypointRenderer.draggedRow !== row) {
                row.classList.add('drag-over');
            }
            return false;
        });
        
        // Drag leave
        row.addEventListener('dragleave', function(e) {
            row.classList.remove('drag-over');
        });
        
        // Drop
        row.addEventListener('drop', function(e) {
            e.preventDefault();
            e.stopPropagation();
            
            if (window.TripWeather.Managers.WaypointRenderer.draggedRow && window.TripWeather.Managers.WaypointRenderer.draggedRow !== row) {
                const draggedSequence = parseInt(window.TripWeather.Managers.WaypointRenderer.draggedRow.dataset.waypointSequence);
                const targetSequence = parseInt(row.dataset.waypointSequence);
                
                console.log('Drop detected - dragged:', draggedSequence, 'target:', targetSequence);
                window.TripWeather.Managers.Waypoint.reorderWaypoints(draggedSequence, targetSequence);
            }
            
            return false;
        });
    },

    /**
     * Setup drop zone for dragging to end
     * @param {HTMLTableRowElement} dropZoneRow - Drop zone row element
     */
    setupDropZone: function(dropZoneRow) {
        dropZoneRow.addEventListener('dragover', function(e) {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            
            if (window.TripWeather.Managers.WaypointRenderer.draggedRow) {
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
            
            if (window.TripWeather.Managers.WaypointRenderer.draggedRow) {
                const draggedSequence = parseInt(window.TripWeather.Managers.WaypointRenderer.draggedRow.dataset.waypointSequence);
                window.TripWeather.Managers.Waypoint.moveToEnd(draggedSequence);
            }
            
            return false;
        });
    },

    /**
     * Currently dragged row (for drag and drop operations)
     */
    draggedRow: null
};
