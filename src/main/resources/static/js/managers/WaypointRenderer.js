/**
 * Waypoint Renderer
 * Handles rendering of waypoints in table and on map
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.WaypointRenderer = {
    
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
        
        marker.waypointId = waypoint.id;
        
        marker.on('click', function() {
            this.highlightTableRow(waypoint.id);
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
            row.dataset.waypointId = waypoint.id;
            
            const weatherHtml = window.TripWeather.Services.Weather.generateWeatherHtml(waypoint.weather, waypoint.weatherLoading);
            
            row.innerHTML = `
                <td class="drag-handle-cell"><span class="drag-handle" title="Drag to reorder">☰</span></td>
                <td>${index + 1}</td>
                <td><input type="date" value="${waypoint.date}" onchange="window.TripWeather.Managers.Waypoint.updateWaypointField(${waypoint.id}, 'date', this.value)"></td>
                <td><input type="time" value="${waypoint.time}" onchange="window.TripWeather.Managers.Waypoint.updateWaypointField(${waypoint.id}, 'time', this.value)"></td>
                <td>${waypoint.timezone || '-'}</td>
                <td>
                    <div class="duration-input-container">
                        <input type="text" 
                               value="${window.TripWeather.Utils.Duration.formatDuration(waypoint.duration)}" 
                               placeholder="3d2h10m" 
                               onblur="window.TripWeather.Managers.Waypoint.updateWaypointDuration(${waypoint.id}, this.value)"
                               onkeydown="window.TripWeather.Managers.WaypointRenderer.handleDurationKeydown(event, ${waypoint.id}, this.value)"
                               class="duration-input"
                               title="Enter duration like 3d2h10m, 48h22m, 1000m, 1.5h">
                        <div class="duration-arrows">
                            <button class="duration-arrow-up" onclick="window.TripWeather.Managers.Waypoint.incrementWaypointDuration(${waypoint.id}, 10)" title="Add 10 minutes">▲</button>
                            <button class="duration-arrow-down" onclick="window.TripWeather.Managers.Waypoint.incrementWaypointDuration(${waypoint.id}, -10)" title="Subtract 10 minutes">▼</button>
                        </div>
                    </div>
                </td>
                <td><input type="text" value="${waypoint.locationName}" placeholder="Enter location name" onchange="window.TripWeather.Managers.Waypoint.updateWaypointField(${waypoint.id}, 'locationName', this.value)"></td>
                ${weatherHtml}
                <td class="actions-cell">
                    <button class="action-btn" onclick="window.TripWeather.Managers.Waypoint.centerOnWaypoint(${waypoint.id})" title="Center on waypoint">
                        <span class="action-icon-container" data-icon="icons/crosshair.svg"></span>
                    </button>
                    <button class="action-btn" onclick="window.TripWeather.Managers.Waypoint.selectNewLocationForWaypoint(${waypoint.id})" title="Select new location on map">
                        <span class="action-icon-container" data-icon="icons/map_pin.svg"></span>
                    </button>
                    <button class="action-btn" onclick="window.TripWeather.Managers.Search.searchNewLocationForWaypoint(${waypoint.id})" title="Search for new location">
                        <span class="action-icon-container" data-icon="icons/search.svg"></span>
                    </button>
                    <button class="action-btn delete-action" onclick="window.TripWeather.Managers.Waypoint.deleteWaypoint(${waypoint.id})" title="Delete waypoint">
                        <span class="action-icon-container" data-icon="icons/delete.svg"></span>
                    </button>
                </td>
            `;
            
            // Initialize drag and drop for the row
            this.setupDragAndDrop(row);
            
            // Setup row click handler
            row.addEventListener('click', function(e) {
                if (e.target.tagName !== 'INPUT' && e.target.tagName !== 'BUTTON') {
                    this.highlightTableRow(waypoint.id);
                    const marker = window.TripWeather.Managers.Waypoint.waypointMarkers.find(m => m.waypointId === waypoint.id);
                    if (marker) {
                        const map = window.TripWeather.Managers.Map.getMap();
                        if (map) {
                            map.setView(marker.getLatLng(), 13);
                        }
                        marker.openPopup();
                    }
                }
            }.bind(this));
            
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
     * Handle keyboard input for duration field
     * @param {Event} event - Keyboard event
     * @param {number} waypointId - ID of the waypoint
     * @param {string} currentValue - Current input value
     */
    handleDurationKeydown: function(event, waypointId, currentValue) {
        if (event.key === 'Enter') {
            event.preventDefault();
            window.TripWeather.Managers.Waypoint.updateWaypointDuration(waypointId, currentValue);
            event.target.blur();
        } else if (event.key === 'Escape') {
            event.preventDefault();
            // Revert to current waypoint value
            const waypoint = window.TripWeather.Managers.Waypoint.getWaypoint(waypointId);
            if (waypoint) {
                event.target.value = window.TripWeather.Utils.Duration.formatDuration(waypoint.duration);
            }
            event.target.blur();
        }
    },

    /**
     * Highlight table row for waypoint
     * @param {number} waypointId - Waypoint ID
     */
    highlightTableRow: function(waypointId) {
        document.querySelectorAll('#waypoints-tbody tr').forEach(row => {
            row.classList.remove('selected');
        });
        
        const row = document.querySelector(`#waypoints-tbody tr[data-waypoint-id="${waypointId}"]`);
        if (row) {
            row.classList.add('selected');
        }
    },

    /**
     * Update marker with weather information
     * @param {object} waypoint - Waypoint object
     */
    updateMarkerWithWeather: function(waypoint) {
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.id === waypoint.id);
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
        const index = window.TripWeather.Managers.Waypoint.waypoints.findIndex(w => w.id === waypoint.id);
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
            e.dataTransfer.setData('text/plain', row.dataset.waypointId);
            console.log('Drag started for waypoint:', row.dataset.waypointId);
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
                const draggedId = parseInt(window.TripWeather.Managers.WaypointRenderer.draggedRow.dataset.waypointId);
                const targetId = parseInt(row.dataset.waypointId);
                
                console.log('Drop detected - dragged:', draggedId, 'target:', targetId);
                window.TripWeather.Managers.Waypoint.reorderWaypoints(draggedId, targetId);
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
                const draggedId = parseInt(window.TripWeather.Managers.WaypointRenderer.draggedRow.dataset.waypointId);
                window.TripWeather.Managers.Waypoint.moveToEnd(draggedId);
            }
            
            return false;
        });
    },

    /**
     * Currently dragged row (for drag and drop operations)
     */
    draggedRow: null
};
