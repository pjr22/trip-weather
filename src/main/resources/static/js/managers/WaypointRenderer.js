/**
 * Waypoint Renderer
 * Handles rendering of waypoints in table and on map
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.WaypointRenderer = {

    /**
     * Sequences of waypoints whose card is currently expanded on mobile.
     * Cards default to collapsed; this set is mutated by the chevron
     * click handler. updateTable() consults it on each rebuild so user
     * expand/collapse choices survive field-edit re-renders.
     */
    expandedWaypoints: new Set(),

    /**
     * Format an ISO date (YYYY-MM-DD) as MM/DD/YY for the mobile date
     * overlay. Returns '' for empty/malformed input. Used only for
     * display — the underlying <input type="date"> still stores the
     * full ISO value and drives the native picker.
     * @param {string} isoDate
     * @returns {string}
     */
    formatShortDate: function(isoDate) {
        if (!isoDate || !/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return '';
        const [y, m, d] = isoDate.split('-');
        return `${m}/${d}/${y.slice(2)}`;
    },

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
        const helpers = window.TripWeather.Utils.Helpers;
        const lastWaypointNumber = window.TripWeather.Managers.Waypoint.getLastWaypointNumber();
        const isStart = orderNumber === 1;
        const isEnd = orderNumber === lastWaypointNumber;
        let waypointLabel = isStart ? 'Start' : isEnd ? 'End' : `Waypoint ${orderNumber}`;
        const safeWaypointLabel = helpers.escapeHtml(waypointLabel);
        const safeLat = helpers.escapeHtml(waypoint.lat);
        const safeLng = helpers.escapeHtml(waypoint.lng);
        let popupContent = `<strong>${safeWaypointLabel}</strong><br>`;
        popupContent += `Latitude: ${safeLat}<br>`;
        popupContent += `Longitude: ${safeLng}<br>`;
        popupContent += `Elevation: ${window.TripWeather.Utils.Helpers.formatElevation(waypoint.alt)}<br>`;
        
        if (waypoint.locationName) {
            const safeLocationName = helpers.escapeHtml(waypoint.locationName);
            popupContent += `<br><strong>${safeLocationName}</strong><br>`;
            
            // Add timezone name after location name
            if (waypoint.timezoneName) {
                const safeTimezoneName = helpers.escapeHtml(waypoint.timezoneName);
                popupContent += `<small>Timezone: ${safeTimezoneName}</small><br>`;
            }
        }
        
        // Add Arrival Time
        const arrivalTime = this.formatWaypointTime(waypoint, false);
        if (!isStart && arrivalTime) {
            popupContent += `Arrival Time: ${helpers.escapeHtml(arrivalTime)}<br>`;
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
                popupContent += `Departure Time: ${helpers.escapeHtml(departureTime)}<br>`;
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
            popupContent += `${helpers.escapeHtml(durationText)}<br>`;
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
        
        const helpers = window.TripWeather.Utils.Helpers;
        const waypoints = window.TripWeather.Managers.Waypoint.getAllWaypoints();
        
        waypoints.forEach((waypoint, index) => {
            const row = tbody.insertRow();
            row.dataset.waypointSequence = waypoint.sequence;
            // Cards default to collapsed on mobile — tap chevron to expand.
            // The class has no visual effect on desktop (where the table
            // layout always shows all columns). Honor any prior expand
            // choice for this sequence so editing fields doesn't collapse
            // the card the user is actively working in.
            const isExpanded = this.expandedWaypoints.has(waypoint.sequence);
            if (!isExpanded) {
                row.classList.add('collapsed');
            }
            
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

            const safeDateValue = helpers.escapeHtml(waypoint.date || '');
            // Mobile-only short-year overlay text (MM/DD/YY) shown on
            // top of the native date input via .date-display. Empty
            // string when no date set — CSS shows a "MM/DD/YY"
            // placeholder via :empty::before instead.
            const safeShortDate = helpers.escapeHtml(this.formatShortDate(waypoint.date));
            const safeDurationValue = helpers.escapeHtml(window.TripWeather.Utils.Duration.formatDuration(waypoint.duration));
            const safeLocationValue = helpers.escapeHtml(waypoint.locationName || '');
            // Distance value: "START" for waypoint 1 (no previous), the
            // rendered miles for later waypoints, empty for any waypoint
            // missing a computed distance. Drop the decimal once distance
            // exceeds 99.9 mi — at trip-scale ranges the tenth-of-a-mile
            // precision stops being useful and just costs a character.
            let distanceText;
            if (index === 0) {
                distanceText = 'START';
            } else if (waypoint.distance) {
                const miles = waypoint.distance;
                distanceText = miles > 99.9 ? `${Math.round(miles)} mi` : `${miles.toFixed(1)} mi`;
            } else {
                distanceText = '';
            }
            const safeDistanceValue = helpers.escapeHtml(distanceText);
            const distanceCellClass = (index === 0) ? 'distance-cell waypoint-start' : 'distance-cell';
            
            row.innerHTML = `
                <td class="drag-handle-cell">
                    <button class="card-toggle" data-action="toggle-card" aria-label="Toggle waypoint details" aria-expanded="${isExpanded ? 'true' : 'false'}"><span class="chevron">▾</span></button>
                    <span class="drag-handle" title="Drag to reorder">☰</span>
                </td>
                <td>${index + 1}</td>
                <td><div class="date-input-wrapper"><input type="date" value="${safeDateValue}" data-waypoint-sequence="${waypoint.sequence}" data-field="date"><span class="date-display">${safeShortDate}</span></div></td>
                <td>${this.buildTimePickerHtml(waypoint, timezoneDisplay)}</td>
                <td>
                    <div class="duration-input-container">
                        <input type="text"
                               value="${safeDurationValue}"
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
                <td><input type="text" value="${safeLocationValue}" placeholder="Enter location name" data-waypoint-sequence="${waypoint.sequence}" data-field="locationName"></td>
                <td class="${distanceCellClass}">${safeDistanceValue}</td>
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
     * Split a stored 24-hour "HH:MM" string into 12-hour display parts.
     * Returns empty fields when the value is absent or malformed so the
     * dropdowns render as "--:-- AM".
     * @param {string} timeStr
     * @returns {{hour: string, minute: string, ampm: string}}
     */
    parseTime: function(timeStr) {
        if (!timeStr || !/^\d{1,2}:\d{2}$/.test(timeStr)) {
            return { hour: '', minute: '', ampm: 'AM' };
        }
        const [h24Str, minute] = timeStr.split(':');
        const h24 = parseInt(h24Str, 10);
        const ampm = h24 >= 12 ? 'PM' : 'AM';
        const hour12 = h24 % 12 || 12;
        return { hour: String(hour12).padStart(2, '0'), minute: minute, ampm: ampm };
    },

    /**
     * Compose a stored 24-hour "HH:MM" string from the three picker selects.
     * Returns '' if hour or minute is blank so downstream code sees "no time".
     * @param {string} hour - "1".."12"
     * @param {string} minute - "00".."59"
     * @param {string} ampm - "AM" | "PM"
     * @returns {string}
     */
    composeTime: function(hour, minute, ampm) {
        if (!hour || !minute) {
            return '';
        }
        const h12 = parseInt(hour, 10);
        let h24 = h12 % 12;
        if (ampm === 'PM') {
            h24 += 12;
        }
        return `${String(h24).padStart(2, '0')}:${minute}`;
    },

    /**
     * Render the hour / minute / AM|PM select trio plus an inline timezone
     * abbreviation for a waypoint. Minute options are 5-minute increments;
     * if the stored value isn't on the grid (legacy data), it's inserted so
     * nothing is silently rounded.
     * @param {object} waypoint
     * @param {string} timezoneDisplay - Pre-computed timezone abbreviation (e.g. "MDT")
     * @returns {string} HTML
     */
    buildTimePickerHtml: function(waypoint, timezoneDisplay) {
        const parsed = this.parseTime(waypoint.time);
        const sequence = waypoint.sequence;
        const safeTz = window.TripWeather.Utils.Helpers.escapeHtml(timezoneDisplay || '');

        const hourOptions = ['<option value="">--</option>'];
        for (let h = 1; h <= 12; h++) {
            const hh = String(h).padStart(2, '0');
            const selected = parsed.hour === hh ? ' selected' : '';
            hourOptions.push(`<option value="${hh}"${selected}>${hh}</option>`);
        }

        const minuteValues = [];
        for (let m = 0; m < 60; m += 5) {
            minuteValues.push(String(m).padStart(2, '0'));
        }
        if (parsed.minute && !minuteValues.includes(parsed.minute)) {
            minuteValues.push(parsed.minute);
            minuteValues.sort();
        }
        const minuteOptions = ['<option value="">--</option>'];
        minuteValues.forEach(mm => {
            const selected = parsed.minute === mm ? ' selected' : '';
            minuteOptions.push(`<option value="${mm}"${selected}>${mm}</option>`);
        });

        const amSelected = parsed.ampm === 'AM' ? ' selected' : '';
        const pmSelected = parsed.ampm === 'PM' ? ' selected' : '';

        return `
            <div class="time-picker">
                <select data-waypoint-sequence="${sequence}" data-time-part="hour" aria-label="Hour">${hourOptions.join('')}</select>
                <span class="time-picker-colon">:</span>
                <select data-waypoint-sequence="${sequence}" data-time-part="minute" aria-label="Minute">${minuteOptions.join('')}</select>
                <select data-waypoint-sequence="${sequence}" data-time-part="ampm" aria-label="AM or PM"><option value="AM"${amSelected}>AM</option><option value="PM"${pmSelected}>PM</option></select>
                <span class="time-picker-tz">${safeTz}</span>
            </div>
        `;
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

        // Time picker: three selects (hour / minute / AM|PM) compose a single HH:MM value.
        // Only commit when the composed value actually differs from what's stored —
        // otherwise a partial selection (just hour, or just minute) would store '',
        // trigger updateTable(), and the rebuild would wipe the user's in-progress picks.
        row.querySelectorAll('select[data-time-part]').forEach(select => {
            select.addEventListener('change', () => {
                const picker = select.closest('.time-picker');
                const hourSelect = picker.querySelector('[data-time-part="hour"]');
                const minuteSelect = picker.querySelector('[data-time-part="minute"]');
                const ampmSelect = picker.querySelector('[data-time-part="ampm"]');

                // If the user picks an hour while minute is still blank, default minute to "00"
                // so a single choice yields a complete time.
                if (select.dataset.timePart === 'hour' && hourSelect.value && !minuteSelect.value) {
                    minuteSelect.value = '00';
                }

                const sequence = parseInt(select.dataset.waypointSequence);
                const composed = this.composeTime(hourSelect.value, minuteSelect.value, ampmSelect.value);
                const current = (waypoint && waypoint.time) || '';
                if (composed !== current) {
                    window.TripWeather.Managers.Waypoint.updateWaypointField(sequence, 'time', composed);
                }
            });
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
        
        // Card collapse/expand toggle (mobile only, hidden on desktop via CSS).
        // stopPropagation keeps the tap from also triggering the row's
        // marker-popup handler below. The expandedWaypoints set persists
        // the choice across updateTable() rebuilds (e.g. when the user
        // edits a date/time/duration field, which triggers a re-render).
        const cardToggle = row.querySelector('button[data-action="toggle-card"]');
        if (cardToggle) {
            const renderer = this;
            cardToggle.addEventListener('click', function(e) {
                e.stopPropagation();
                const collapsed = row.classList.toggle('collapsed');
                cardToggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
                if (collapsed) {
                    renderer.expandedWaypoints.delete(waypoint.sequence);
                } else {
                    renderer.expandedWaypoints.add(waypoint.sequence);
                }
            });
        }

        // Setup row click handler
        row.addEventListener('click', function(e) {
            if (e.target.tagName !== 'INPUT' && e.target.tagName !== 'BUTTON' && e.target.tagName !== 'SELECT') {
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
        const marker = window.TripWeather.Managers.Waypoint.waypointMarkers.find(
            m => m.waypointSequence === waypoint.sequence
        );
        if (!marker) {
            return;
        }

        const orderIndex = window.TripWeather.Managers.Waypoint.waypoints.findIndex(
            w => w.sequence === waypoint.sequence
        );
        const orderNumber = orderIndex !== -1 ? orderIndex + 1 : waypoint.sequence;

        this.updateMarkerPopup(marker, waypoint, orderNumber);
    },

    /**
     * Update marker with location information
     * @param {object} waypoint - Waypoint object
     */
    updateMarkerWithLocation: function(waypoint) {
        const marker = window.TripWeather.Managers.Waypoint.waypointMarkers.find(
            m => m.waypointSequence === waypoint.sequence
        );
        if (!marker) {
            return;
        }

        const orderIndex = window.TripWeather.Managers.Waypoint.waypoints.findIndex(
            w => w.sequence === waypoint.sequence
        );
        const orderNumber = orderIndex !== -1 ? orderIndex + 1 : waypoint.sequence;

        this.updateMarkerPopup(marker, waypoint, orderNumber);
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
     * Close all open pop-ups on the map
     */
    closeAllPopups: function() {
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            map.eachLayer(function(layer) {
                if (layer instanceof L.Marker && layer.getPopup()) {
                    layer.closePopup();
                }
            });
        }
        
        // Also close user location popup if it exists
        if (window.TripWeather.Managers.Map.userLocationMarker) {
            window.TripWeather.Managers.Map.userLocationMarker.closePopup();
        }
    },

    /**
     * Open popup for a specific waypoint
     * @param {number} sequence - Waypoint sequence
     */
    openWaypointPopup: function(sequence) {
        // Close all existing pop-ups first
        this.closeAllPopups();
        
        // Find the marker for this waypoint
        const marker = window.TripWeather.Managers.Waypoint.waypointMarkers.find(
            m => m.waypointSequence === sequence
        );
        
        if (marker) {
            marker.openPopup();
        }
    },

    /**
     * Currently dragged row (for drag and drop operations)
     */
    draggedRow: null
};
