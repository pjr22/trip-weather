/**
 * Navigation Manager
 * Orchestrates a navigation session: position stream → snap → maneuver scheduling
 * → voice + UI updates. Handles lifecycle (start/stop), and entering/exiting the
 * full-screen nav-mode UI.
 *
 * Phase 2 scope: on-route navigation along the currently displayed route, with
 * voice prompts, a maneuver banner, simulated GPS via ?simgps=1, and wake-lock.
 * Phase 3 will add the connector route to "join" the route from off it,
 * off-route detection with guide-back, and waypoint-stop semantics (Continue/Skip).
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.Navigation = {

    active: false,
    starting: false,
    startToken: 0,
    compiled: null,
    maneuvers: [],
    waypointStops: [],
    scheduler: null,
    waypointScheduler: null,
    positionSource: null,
    positionController: null,
    mapAdapter: null,
    lastSegmentIdx: 0,
    lastSavedSegmentIdx: 0,
    connectorEndDistance: 0,
    connectorActive: false,
    // Geometry layout of the current merged polyline. _connectorLen vertices of
    // connector occupy merged indices 0..N-1; saved.slice(_savedSegIdxAtJoin+1)
    // begins at merged index N. Used to translate merged segment indices back
    // to saved-polyline segment indices for off-route forward-snap hints.
    _connectorLen: 0,
    _savedSegIdxAtJoin: -1,
    // Off-route detection (Phase 3c). offRouteSince is the timestamp of the
    // first sustained off-route reading (0 = on-route). lastRerouteAt enforces
    // the cooldown. rerouting guards against overlapping fetches.
    offRouteSince: 0,
    lastRerouteAt: 0,
    rerouting: false,
    // Waypoint stops (Phase 3d/3e). Sets are keyed by waypoint index in
    // routeData.waypoints; carried across re-routes since the trip plan itself
    // doesn't change. pausedAtWaypoint = -1 means "not paused".
    arrivedWaypoints: null,
    skippedWaypoints: null,
    pausedAtWaypoint: -1,
    skipVisibleForWaypoint: -1,
    // Final-destination state. arrivedAtFinal latches once the user enters the
    // arrival window of the polyline end; banner stays "You have arrived at
    // [name]" and the user closes via Exit Navigation. finalSidePhrase is "left",
    // "right", or "" when undeterminable; computed from the polyline's last
    // bearing and the offset to the planned waypoint location.
    arrivedAtFinal: false,
    finalSidePhrase: '',
    finalApproachScheduler: null,
    bannerEl: null,
    exitBtnEl: null,
    continueBtnEl: null,
    skipBtnEl: null,

    // ORS step.type values that mark waypoint positions rather than driving
    // maneuvers. We filter them out of the spoken maneuver list — passthrough
    // (duration == 0) waypoints stay silent, and real-stop (duration > 0)
    // announcements live on the parallel waypointStops[] structure (Phase 3d).
    ORS_STEP_TYPE_GOAL: 10,
    ORS_STEP_TYPE_DEPART: 11,

    initialize: function() {
        const navBtn = document.getElementById('navigate-btn');
        if (navBtn) {
            navBtn.addEventListener('click', this.start.bind(this));
        }
        // VoiceGuide voices load asynchronously; warm them up now so the first
        // utterance after Navigate doesn't get a missing voice.
        if (window.TripWeather.Nav.VoiceGuide) {
            window.TripWeather.Nav.VoiceGuide.initialize();
        }
        this.arrivedWaypoints = {};
        this.skippedWaypoints = {};
        this.updateButtonState();
    },

    /**
     * Show or hide the Navigate button based on whether a route is currently displayed.
     * Disabled while a start sequence is mid-flight (location fix + connector fetch)
     * so a second click can't double-trigger. Called by RouteManager after a route
     * is calculated or cleared, and by start/stop transitions.
     */
    updateButtonState: function() {
        const navBtn = document.getElementById('navigate-btn');
        if (!navBtn) return;
        const routeMgr = window.TripWeather.Managers.Route;
        const hasRoute = routeMgr
            && routeMgr.currentRoute
            && routeMgr.currentRoute.geometry
            && routeMgr.currentRoute.geometry.length >= 2;
        navBtn.disabled = !hasRoute || this.starting || this.active;
    },

    start: function() {
        if (this.active || this.starting) return;

        const routeMgr = window.TripWeather.Managers.Route;
        const routeData = routeMgr ? routeMgr.currentRoute : null;
        if (!routeData || !routeData.geometry || routeData.geometry.length < 2) {
            window.TripWeather.Managers.UI.showToast(
                'Calculate or load a route before starting navigation.', 'warning');
            return;
        }

        // The Navigate click is a user gesture — use it to unlock TTS for the session.
        // iOS Safari otherwise rejects the first speak() call. Must run synchronously
        // even though the rest of start is now async behind a location fix.
        window.TripWeather.Nav.VoiceGuide.unlock();

        // Fresh-session reset of waypoint progress. These sets carry across
        // re-routes within a session but must clear when starting anew.
        this.arrivedWaypoints = {};
        this.skippedWaypoints = {};
        this.pausedAtWaypoint = -1;
        this.skipVisibleForWaypoint = -1;
        this.arrivedAtFinal = false;

        const params = new URLSearchParams(window.location.search);
        const isSimGps = params.get('simgps') === '1';
        // beginAtStart defaults to 1 (preserve today's sim behaviour). Only the
        // explicit "0" opts in to "start from real GPS, capped to D miles from
        // route start" — used to test the connector path without driving.
        const beginAtStart = params.get('beginAtStart') !== '0';

        // Fast path: simulator with default beginAtStart=1 needs no GPS fix.
        // The simulator IS the route, starting at distance 0 of the saved polyline.
        if (isSimGps && beginAtStart) {
            this._beginNavSession(routeData, null);
            return;
        }

        // All other paths (live GPS, or simgps with beginAtStart=0) need a real
        // fix before we can decide whether a connector is needed.
        this.starting = true;
        this.startToken += 1;
        const myToken = this.startToken;
        this.updateButtonState();

        const helpers = window.TripWeather.Utils.Helpers;
        helpers.showLoading('location-loading-overlay');
        let firstFixHandled = false;
        const self = this;

        window.TripWeather.Managers.Map.acquireUserLocation({
            maxCacheAgeMs: 0,
            onPosition: function(position) {
                // The watch keeps refining accuracy after the first fix arrives;
                // we only act on the first one. Late refinements get dropped here.
                if (firstFixHandled || self.startToken !== myToken) return;
                firstFixHandled = true;

                // The route could have been cleared (e.g. waypoint edit) between
                // click and fix arrival. Bail rather than navigate a dead route.
                if (!routeMgr.currentRoute || routeMgr.currentRoute !== routeData) {
                    helpers.hideLoading('location-loading-overlay');
                    self._abortStart(myToken);
                    window.TripWeather.Managers.UI.showToast(
                        'Route was cleared during location fix. Please try again.', 'warning');
                    return;
                }

                let userPos = { lat: position.coords.latitude, lng: position.coords.longitude };
                if (isSimGps && !beginAtStart) {
                    userPos = self._capUserPositionToRouteStart(userPos, routeData.geometry);
                }
                // Overlay stays up through _afterFix → connector fetch → _beginNavSession;
                // hidden at the nav-mode transition or on abort to avoid flicker.
                self._afterFix(routeData, userPos, myToken);
            },
            onError: function(error) {
                if (self.startToken !== myToken) return;
                helpers.hideLoading('location-loading-overlay');
                self._abortStart(myToken);
                console.warn('Navigation: location acquisition failed:',
                    error && error.message, 'code=', error && error.code);
                window.TripWeather.Managers.UI.showToast(
                    window.TripWeather.Utils.GeolocationDiagnostics.describeError(error),
                    'error');
            }
        });
    },

    /**
     * Cap the user's reported position to within SIM_BEGIN_AT_START_MAX_DISTANCE_M
     * of the route's first waypoint. Used only by the simgps+beginAtStart=0 dev
     * mode so devs far from the route can still test the connector path with a
     * sensibly short connector. Linear interpolation in degree-space is accurate
     * enough at single-digit miles; haversine for the distance check.
     */
    _capUserPositionToRouteStart: function(userPos, geometry) {
        const C = window.TripWeather.Nav.Constants;
        const startLng = geometry[0][0];
        const startLat = geometry[0][1];
        const distM = window.TripWeather.Nav.RouteSnapper.haversine(
            { lat: startLat, lng: startLng }, userPos);
        if (distM <= C.SIM_BEGIN_AT_START_MAX_DISTANCE_M) return userPos;
        const fraction = C.SIM_BEGIN_AT_START_MAX_DISTANCE_M / distM;
        return {
            lat: startLat + (userPos.lat - startLat) * fraction,
            lng: startLng + (userPos.lng - startLng) * fraction
        };
    },

    /**
     * After a GPS fix has arrived (and been capped, in beginAtStart=0 mode):
     * snap to the saved polyline. If on-route, begin the session directly. If
     * off-route, fetch a connector via /api/route/calculate and then begin.
     */
    _afterFix: function(routeData, userPos, myToken) {
        const C = window.TripWeather.Nav.Constants;
        const helpers = window.TripWeather.Utils.Helpers;
        const savedCompiled = window.TripWeather.Nav.RouteSnapper.compile(routeData.geometry);
        const snap = window.TripWeather.Nav.RouteSnapper.snap(savedCompiled, userPos, 0);
        if (!snap) {
            helpers.hideLoading('location-loading-overlay');
            this._abortStart(myToken);
            window.TripWeather.Managers.UI.showToast(
                'Could not align your position with the route.', 'error');
            return;
        }

        if (snap.crossTrackM <= C.ON_ROUTE_THRESHOLD_M) {
            // Already on the route — no connector needed. The scheduler will skip
            // past any maneuvers we've already passed when the first position
            // tick arrives mid-route.
            helpers.hideLoading('location-loading-overlay');
            this._beginNavSession(routeData, null);
            return;
        }

        // Extend the connector's destination past the next saved-route maneuver
        // so ORS computes that maneuver's instruction in our actual approach
        // direction. Without this, the saved-route's stored instruction (encoded
        // for the original approach) can be wrong — e.g. "turn left onto X" when
        // joining from the opposite side would actually require a right turn.
        const dest = this._findConnectorDestination(routeData, savedCompiled, snap);

        const reqWaypoints = [
            { latitude: userPos.lat, longitude: userPos.lng,
              name: '', date: '', time: '', duration: 0, timezoneName: '' },
            { latitude: dest.lat, longitude: dest.lng,
              name: '', date: '', time: '', duration: 0, timezoneName: '' }
        ];
        const self = this;
        helpers.httpPost('/api/route/calculate', { waypoints: reqWaypoints })
            .then(function(connector) {
                if (self.startToken !== myToken) return;
                helpers.hideLoading('location-loading-overlay');
                if (!connector || !connector.geometry || connector.geometry.length < 2) {
                    self._abortStart(myToken);
                    window.TripWeather.Managers.UI.showToast(
                        'Could not compute a route to your location.', 'error');
                    return;
                }
                self._beginNavSession(routeData, {
                    connector: connector,
                    snap: snap,
                    destIsPlannedStop: dest.isPlannedStop,
                    targetWaypointIdx: dest.waypointIdx != null ? dest.waypointIdx : -1
                });
            })
            .catch(function(err) {
                if (self.startToken !== myToken) return;
                helpers.hideLoading('location-loading-overlay');
                self._abortStart(myToken);
                console.warn('Navigation: connector fetch failed:', err);
                window.TripWeather.Managers.UI.showToast(
                    'Could not compute a route to your location: ' + (err && err.message || ''),
                    'error');
            });
    },

    _abortStart: function(myToken) {
        if (this.startToken !== myToken) return;
        // Belt-and-braces: most callers hide the overlay themselves before calling
        // abort, but a future caller could miss it. The hide is idempotent.
        window.TripWeather.Utils.Helpers.hideLoading('location-loading-overlay');
        this.starting = false;
        this.updateButtonState();
    },

    /**
     * Pick the destination waypoint we hand to ORS for the connector. Priority:
     *
     *   1. If any non-final waypoint with duration > 0 would be skipped by
     *      perpendicular-foot routing, return that waypoint's location. The
     *      user planned to stop there; the connector must take them through it.
     *   2. Otherwise, look ahead for the next saved-route maneuver and place
     *      the destination a few vertices past it (when reasonably close), so
     *      ORS re-encodes that maneuver in the user's actual approach direction
     *      inside the connector's instruction list — sidestepping the case
     *      where the saved-route's stored instruction was encoded for the
     *      original direction.
     *   3. Fall back to the perpendicular foot when neither applies, or when
     *      the next maneuver is further than CONNECTOR_LOOKAHEAD_MAX_M.
     */
    _findConnectorDestination: function(routeData, savedCompiled, snap, originalIdxFloor) {
        originalIdxFloor = (originalIdxFloor == null) ? -1 : originalIdxFloor;
        const C = window.TripWeather.Nav.Constants;
        const fallback = {
            lat: snap.snappedLat,
            lng: snap.snappedLng,
            isPlannedStop: false
        };

        const stop = this._firstSkippedDurationWaypoint(routeData, snap, originalIdxFloor);
        if (stop) {
            return {
                lat: stop.lat,
                lng: stop.lng,
                isPlannedStop: true,
                waypointIdx: stop.waypointIdx,
                savedPolylineIdx: stop.savedPolylineIdx
            };
        }

        const segments = routeData.segments;
        if (!segments) return fallback;

        let nextManeuverIdx = -1;
        for (let s = 0; s < segments.length && nextManeuverIdx < 0; s++) {
            const segment = segments[s];
            if (!segment.steps) continue;
            for (let k = 0; k < segment.steps.length; k++) {
                const step = segment.steps[k];
                if (step.type === this.ORS_STEP_TYPE_GOAL
                    || step.type === this.ORS_STEP_TYPE_DEPART) continue;
                const wp = step.wayPoints || step.way_points;
                if (!wp || wp.length === 0) continue;
                if (wp[0] > snap.segmentIdx) {
                    nextManeuverIdx = wp[0];
                    break;
                }
            }
        }
        if (nextManeuverIdx < 0) return fallback;

        const distToManeuver = savedCompiled.cumDist[nextManeuverIdx]
            - snap.distanceAlongPolyline;
        if (distToManeuver > C.CONNECTOR_LOOKAHEAD_MAX_M) return fallback;

        const destIdx = Math.min(nextManeuverIdx + C.CONNECTOR_MANEUVER_BUFFER_VERTICES,
                                  routeData.geometry.length - 1);
        return {
            lat: routeData.geometry[destIdx][1],
            lng: routeData.geometry[destIdx][0],
            isPlannedStop: false
        };
    },

    /**
     * Return the location of the first non-final waypoint with duration > 0
     * that perpendicular-foot connector routing would skip — its position on
     * the saved polyline lies at or before the snap segment AND past the user's
     * lower-bound progress (originalIdxFloor). The user planned to stop there,
     * so the connector must end at that waypoint rather than past it.
     *
     * originalIdxFloor lets the off-route guide-back exclude waypoints already
     * visited (earlier in the trip): pass -1 for initial Navigate (all
     * waypoints in scope), pass lastSavedSegmentIdx mid-trip.
     *
     * Polyline indices come from ORS's depart/goal markers (same logic as
     * _buildWaypointStops). Returns null if no such waypoint exists; the caller
     * then proceeds to the maneuver-lookahead heuristic.
     */
    _firstSkippedDurationWaypoint: function(routeData, snap, originalIdxFloor) {
        originalIdxFloor = (originalIdxFloor == null) ? -1 : originalIdxFloor;
        const waypoints = routeData.waypoints;
        const segments = routeData.segments;
        if (!waypoints || !segments) return null;

        const arrived = this.arrivedWaypoints || {};
        const skipped = this.skippedWaypoints || {};

        for (let i = 0; i < waypoints.length - 1; i++) {
            const w = waypoints[i];
            if (!w.duration || w.duration <= 0) continue;
            if (arrived[i] || skipped[i]) continue;

            let polylineIdx;
            if (i === 0) {
                const firstSeg = segments[0];
                const firstStep = firstSeg && firstSeg.steps && firstSeg.steps[0];
                const wp = firstStep && (firstStep.wayPoints || firstStep.way_points);
                polylineIdx = wp && wp.length > 0 ? wp[0] : 0;
            } else {
                const seg = segments[i - 1];
                if (!seg || !seg.steps || seg.steps.length === 0) continue;
                const lastStep = seg.steps[seg.steps.length - 1];
                const wp = lastStep.wayPoints || lastStep.way_points;
                polylineIdx = wp && wp.length > 0
                    ? wp[wp.length - 1] : routeData.geometry.length - 1;
            }

            if (polylineIdx > originalIdxFloor && polylineIdx <= snap.segmentIdx) {
                return {
                    lat: routeData.geometry[polylineIdx][1],
                    lng: routeData.geometry[polylineIdx][0],
                    waypointIdx: i,
                    savedPolylineIdx: polylineIdx
                };
            }
        }
        return null;
    },

    /**
     * Final synchronous startup once everything we need is in hand. With a
     * connector, splits visual trim from data trim:
     *
     *   - Visual: dashed-orange polyline ends at the first connector vertex on
     *     the saved polyline. After that the user's path coincides with the
     *     blue saved polyline, so drawing more orange would be redundant.
     *   - Data:   merged polyline keeps the connector's full geometry and
     *     splices the saved polyline starting after the connector's last
     *     vertex Q. The full connector geometry stays in the path so all of
     *     ORS's connector instructions (including any maneuver past the visual
     *     trim that ORS encoded for our approach direction) survive in the
     *     merged maneuver list.
     *
     * Saved-route maneuvers are filtered to those past Q's saved segment, since
     * the connector now "owns" everything up to Q. Without this, the saved
     * route's instructions (encoded for the original approach direction) could
     * conflict with the connector's instructions for the same junction.
     */
    _beginNavSession: function(routeData, connectorInfo) {
        // Side phrase for final-arrival prompts is fixed for the saved route;
        // compute once before assembly. Re-routes don't change the side because
        // the saved polyline's last segment is preserved across them.
        this.finalSidePhrase = this._computeFinalArrivalSide(routeData);

        const assembled = this._assembleMergedRoute(routeData, connectorInfo);
        this._applyAssembled(assembled);

        this.mapAdapter = window.TripWeather.Nav.MapAdapter.Leaflet;
        this.mapAdapter.initialize(window.TripWeather.Managers.Map.getMap());
        this.mapAdapter.enterNavMode();
        if (assembled.connectorPolylineCoords) {
            this.mapAdapter.drawConnectorPolyline(assembled.connectorPolylineCoords);
        }

        this._enterNavUI();

        window.TripWeather.Nav.WakeLock.request();

        const maneuverDistances = this.maneuvers.map(function(m) { return m.distanceFromStart; });
        const stopDistances = this.waypointStops
            .filter(function(s) { return !s.isFinal && s.duration > 0; })
            .map(function(s) { return s.distanceFromStart; });
        this.positionSource = window.TripWeather.Nav.PositionSource.fromUrl(
            assembled.activeGeometry,
            { maneuverDistances: maneuverDistances, stopDistances: stopDistances }
        );
        const self = this;
        this.positionController = this.positionSource.start(
            function(pos) { self._onPosition(pos); },
            function(err) { self._onPositionError(err); }
        );

        this.starting = false;
        this.active = true;
        this.updateButtonState();
        window.TripWeather.Nav.VoiceGuide.say('Starting navigation.');
    },

    /**
     * Assemble a merged route from a saved-route + optional connector. Returns
     * everything needed to replace the active route — the merged geometry, the
     * compiled snapper, maneuver list, waypoint stops, the connector polyline
     * coords for visual, and the geometry-layout fields used to translate
     * merged segment indices back to saved-polyline segment indices.
     *
     * Stateless: doesn't mutate the manager. Callers (initial start, mid-trip
     * re-route) apply the result via _applyAssembled.
     */
    _assembleMergedRoute: function(routeData, connectorInfo) {
        const RouteSnapper = window.TripWeather.Nav.RouteSnapper;

        if (!connectorInfo) {
            const compiled = RouteSnapper.compile(routeData.geometry);
            return {
                activeGeometry: routeData.geometry,
                compiled: compiled,
                maneuvers: this._flattenManeuvers(routeData, compiled, 0, -1),
                waypointStops: this._buildWaypointStops(routeData, compiled, 0, -1),
                connectorPolylineCoords: null,
                connectorEndDistance: 0,
                connectorActive: false,
                connectorLen: 0,
                savedSegIdxAtJoin: -1
            };
        }

        const conn = connectorInfo.connector;
        const C = window.TripWeather.Nav.Constants;
        const savedCompiled = RouteSnapper.compile(routeData.geometry);

        // Visual trim policy depends on the destination:
        //   - Planned stop (duration > 0 waypoint): show the full connector so
        //     the user can see where they're heading. Some orange-on-blue
        //     overlap is acceptable as the price of showing the actual target.
        //   - Otherwise (perpendicular foot, maneuver lookahead, off-route
        //     forward snap): trim at the first connector vertex within
        //     ON_ROUTE_THRESHOLD_M of the saved polyline so we don't draw
        //     orange on top of the saved blue along shared stretches.
        let visualEndIdx;
        if (connectorInfo.destIsPlannedStop) {
            visualEndIdx = conn.geometry.length - 1;
        } else {
            let firstOnRouteIdx = -1;
            for (let i = 0; i < conn.geometry.length; i++) {
                const c = conn.geometry[i];
                const cSnap = RouteSnapper.snap(savedCompiled, { lat: c[1], lng: c[0] }, 0);
                if (cSnap && cSnap.crossTrackM <= C.ON_ROUTE_THRESHOLD_M) {
                    firstOnRouteIdx = i;
                    break;
                }
            }
            visualEndIdx = firstOnRouteIdx >= 0
                ? firstOnRouteIdx
                : conn.geometry.length - 1;
        }

        // Data splice: saved-polyline segment of the connector's last vertex.
        // Saved geometry past that segment is what we keep; saved maneuvers up
        // to and including that segment are dropped (the connector owns those,
        // with correctly-oriented instructions).
        const last = conn.geometry[conn.geometry.length - 1];
        const qSnap = RouteSnapper.snap(
            savedCompiled, { lat: last[1], lng: last[0] }, 0);
        const qSegIdx = qSnap ? qSnap.segmentIdx : connectorInfo.snap.segmentIdx;

        const activeGeometry = conn.geometry.concat(routeData.geometry.slice(qSegIdx + 1));
        const connectorPolylineCoords = conn.geometry
            .slice(0, visualEndIdx + 1)
            .map(function(c) { return [c[1], c[0]]; });

        const compiled = RouteSnapper.compile(activeGeometry);
        const M = conn.geometry.length;
        const connectorEndDistance = compiled.cumDist[visualEndIdx] || 0;
        const indexShift = M - qSegIdx - 1;
        const connectorManeuvers = this._flattenManeuvers(conn, compiled, 0, -1);
        const savedManeuvers = this._flattenManeuvers(routeData, compiled, indexShift, qSegIdx);

        let waypointStops = this._buildWaypointStops(routeData, compiled, indexShift, qSegIdx);

        // When the connector destination IS a duration waypoint (3b's planned-
        // stop case, or 3e's reroute-to-missed-waypoint), that waypoint is at
        // or behind the qSegIdx floor and gets filtered out of waypointStops.
        // Re-inject it as a synthetic entry at the connector's end so arrival
        // detection (Phase 3d) fires when the user reaches the connector's
        // last vertex.
        const targetIdx = (connectorInfo.targetWaypointIdx != null)
            ? connectorInfo.targetWaypointIdx : -1;
        if (targetIdx >= 0 && routeData.waypoints && routeData.waypoints[targetIdx]) {
            const wpData = routeData.waypoints[targetIdx];
            const isFinal = targetIdx === routeData.waypoints.length - 1;
            const alreadyListed = waypointStops.some(function(s) {
                return s.waypointIdx === targetIdx;
            });
            if (!alreadyListed && (wpData.duration || 0) > 0 && !isFinal) {
                // Pull waypointLat/Lng from the waypoint's planned location so
                // the geographic arrival backstop has the right reference point
                // (the merged polyline doesn't pass through this location — it
                // ends at Q ≈ this location and continues on saved.slice(K+1)).
                const wpLng = wpData.location && wpData.location[0] != null
                    ? wpData.location[0] : null;
                const wpLat = wpData.location && wpData.location[1] != null
                    ? wpData.location[1] : null;
                waypointStops = [{
                    waypointIdx: targetIdx,
                    polylineIdx: M - 1,
                    distanceFromStart: compiled.cumDist[M - 1] || 0,
                    duration: wpData.duration,
                    name: wpData.name || ('Waypoint ' + (targetIdx + 1)),
                    waypointLat: wpLat,
                    waypointLng: wpLng,
                    isFinal: false
                }].concat(waypointStops);
            }
        }

        return {
            activeGeometry: activeGeometry,
            compiled: compiled,
            maneuvers: connectorManeuvers.concat(savedManeuvers),
            waypointStops: waypointStops,
            connectorPolylineCoords: connectorPolylineCoords,
            connectorEndDistance: connectorEndDistance,
            connectorActive: true,
            connectorLen: M,
            savedSegIdxAtJoin: qSegIdx
        };
    },

    /**
     * Apply an assembled merged route to manager state. Resets per-route runtime
     * state (scheduler, lastSegmentIdx, off-route timer, etc.) so the new route
     * is followed from its beginning. Map adapter / position source / nav UI
     * are NOT touched — callers handle those (initial Navigate sets them up,
     * mid-trip re-route swaps the connector polyline visual but keeps the rest).
     */
    _applyAssembled: function(assembled) {
        this.compiled = assembled.compiled;
        this.maneuvers = assembled.maneuvers;
        this.waypointStops = assembled.waypointStops;
        this.connectorEndDistance = assembled.connectorEndDistance;
        this.connectorActive = assembled.connectorActive;
        this._connectorLen = assembled.connectorLen;
        this._savedSegIdxAtJoin = assembled.savedSegIdxAtJoin;
        this.scheduler = window.TripWeather.Nav.ManeuverScheduler.create(this.maneuvers);
        this._rebuildWaypointScheduler();
        this._rebuildFinalApproachScheduler();
        this.lastSegmentIdx = 0;
        this.lastSavedSegmentIdx = assembled.savedSegIdxAtJoin >= 0
            ? assembled.savedSegIdxAtJoin : 0;
        this.offRouteSince = 0;
    },

    /**
     * Build (or rebuild) the one-element final-destination approach scheduler.
     * The destination's distanceFromStart is the merged polyline's total length;
     * sidePhrase ('left' / 'right' / '') was computed in _beginNavSession.
     */
    _rebuildFinalApproachScheduler: function() {
        if (!this.compiled || !this.compiled.totalDistance) {
            this.finalApproachScheduler = null;
            return;
        }
        const stop = {
            distanceFromStart: this.compiled.totalDistance,
            sidePhrase: this.finalSidePhrase || ''
        };
        this.finalApproachScheduler = window.TripWeather.Nav.ManeuverScheduler
            .createForFinalDestination(stop);
    },

    /**
     * Side ('left' / 'right' / '') of the final destination relative to the
     * polyline's last segment direction. Determined by the cross product of
     * (last-segment bearing) × (offset from polyline endpoint to waypoint
     * location). Returns '' when the offset is below ~5 m — the destination is
     * essentially on the road and "side" isn't meaningful.
     */
    _computeFinalArrivalSide: function(routeData) {
        if (!routeData || !routeData.waypoints || !routeData.geometry) return '';
        const geo = routeData.geometry;
        if (geo.length < 2) return '';
        const finalIdx = routeData.waypoints.length - 1;
        const finalWp = routeData.waypoints[finalIdx];
        if (!finalWp) return '';

        // Waypoint location: prefer the routeData.waypoints entry's coordinates
        // (which carry the raw lat/lng of the planned stop) over the polyline
        // endpoint, since the polyline ends on the road and the waypoint may sit
        // off it.
        const wpLng = (finalWp.location && finalWp.location[0] != null)
            ? finalWp.location[0] : geo[geo.length - 1][0];
        const wpLat = (finalWp.location && finalWp.location[1] != null)
            ? finalWp.location[1] : geo[geo.length - 1][1];

        const last = geo[geo.length - 1];
        const prev = geo[geo.length - 2];
        const lastLng = last[0], lastLat = last[1];
        const prevLng = prev[0], prevLat = prev[1];

        // Offset distance from polyline endpoint to waypoint location.
        const offsetDist = window.TripWeather.Nav.RouteSnapper.haversine(
            { lat: lastLat, lng: lastLng }, { lat: wpLat, lng: wpLng });
        if (offsetDist < 5) return '';

        // 2D cross product: (bearing) × (offset). Positive = waypoint is left
        // of the bearing direction; negative = right. lng is x (east), lat is
        // y (north).
        const bx = lastLng - prevLng, by = lastLat - prevLat;
        const ox = wpLng - lastLng, oy = wpLat - lastLat;
        const cross = bx * oy - by * ox;
        if (Math.abs(cross) < 1e-12) return '';
        return cross > 0 ? 'left' : 'right';
    },

    /**
     * Build (or rebuild) the waypoint-approach scheduler over the currently
     * eligible duration waypoints — non-final, duration > 0, not yet arrived,
     * not yet skipped. Called on every route apply, and when the eligibility
     * set changes (waypoint arrival / skip / drive-past acknowledged).
     */
    _rebuildWaypointScheduler: function() {
        const arrived = this.arrivedWaypoints || {};
        const skipped = this.skippedWaypoints || {};
        const eligible = (this.waypointStops || []).filter(function(stop) {
            if (stop.isFinal) return false;
            if (!stop.duration || stop.duration <= 0) return false;
            if (arrived[stop.waypointIdx]) return false;
            if (skipped[stop.waypointIdx]) return false;
            return true;
        });
        this.waypointScheduler = window.TripWeather.Nav.ManeuverScheduler
            .createForWaypointStops(eligible);
    },

    /**
     * Off-route guide-back. Forward-snap the user onto the saved polyline ahead
     * of where they've been, fetch a connector from current position to that
     * forward join, splice the new connector into the active route. The
     * REROUTE_COOLDOWN_MS gate (set on lastRerouteAt before the fetch) keeps
     * this from thrashing if the connector itself can't bring the user back —
     * even when the API call fails the cooldown still applies.
     */
    _triggerReroute: function(currentPos, options) {
        if (this.rerouting) return;
        options = options || {};
        this.rerouting = true;
        this.lastRerouteAt = Date.now();
        this.offRouteSince = 0;

        const VoiceGuide = window.TripWeather.Nav.VoiceGuide;
        VoiceGuide.cancel();
        VoiceGuide.say(options.voicePhrase || 'Re-routing.');

        const routeMgr = window.TripWeather.Managers.Route;
        const routeData = routeMgr ? routeMgr.currentRoute : null;
        if (!routeData) {
            this.rerouting = false;
            return;
        }

        const RouteSnapper = window.TripWeather.Nav.RouteSnapper;
        const savedCompiled = RouteSnapper.compile(routeData.geometry);

        // Forward snap is needed for the spliced saved-route tail regardless of
        // whether the connector destination is auto-determined or explicit.
        const snap = RouteSnapper.snap(
            savedCompiled, currentPos, this.lastSavedSegmentIdx, { forwardOnly: true });
        if (!snap) {
            this.rerouting = false;
            window.TripWeather.Managers.UI.showToast(
                'Could not find a forward point on the route to re-join.', 'warning');
            return;
        }

        // Caller may supply an explicit destination (3e drive-past targets the
        // missed waypoint itself). Otherwise auto-pick via the same priorities
        // as initial Navigate (skipped duration waypoint → maneuver lookahead →
        // perpendicular foot).
        let dest;
        if (options.dest) {
            dest = {
                lat: options.dest.lat,
                lng: options.dest.lng,
                isPlannedStop: !!options.destIsPlannedStop,
                waypointIdx: options.targetWaypointIdx != null
                    ? options.targetWaypointIdx : -1
            };
        } else {
            dest = this._findConnectorDestination(
                routeData, savedCompiled, snap, this.lastSavedSegmentIdx);
        }

        const reqWaypoints = [
            { latitude: currentPos.lat, longitude: currentPos.lng,
              name: '', date: '', time: '', duration: 0, timezoneName: '' },
            { latitude: dest.lat, longitude: dest.lng,
              name: '', date: '', time: '', duration: 0, timezoneName: '' }
        ];

        const helpers = window.TripWeather.Utils.Helpers;
        const self = this;
        helpers.httpPost('/api/route/calculate', { waypoints: reqWaypoints })
            .then(function(connector) {
                if (!self.active) return;
                if (!connector || !connector.geometry || connector.geometry.length < 2) {
                    self.rerouting = false;
                    window.TripWeather.Managers.UI.showToast(
                        'Re-route failed. Continuing on current path.', 'error');
                    return;
                }
                self._applyReroute(routeData, {
                    connector: connector,
                    snap: snap,
                    destIsPlannedStop: dest.isPlannedStop,
                    targetWaypointIdx: dest.waypointIdx != null ? dest.waypointIdx : -1
                });
            })
            .catch(function(err) {
                if (!self.active) return;
                console.warn('Navigation: re-route fetch failed:', err);
                self.rerouting = false;
                window.TripWeather.Managers.UI.showToast(
                    'Re-route failed. Continuing on current path.', 'error');
            });
    },

    /**
     * Swap in a re-routed merged polyline. Replaces the dashed-orange visual
     * (a fresh connector for the new join) and, for the simulator playback
     * source, restarts emission over the new geometry. Live GPS doesn't depend
     * on geometry so its watchPosition keeps streaming uninterrupted.
     */
    _applyReroute: function(routeData, connectorInfo) {
        const assembled = this._assembleMergedRoute(routeData, connectorInfo);
        this._applyAssembled(assembled);

        if (this.mapAdapter) {
            this.mapAdapter.clearConnectorPolyline();
            if (assembled.connectorPolylineCoords) {
                this.mapAdapter.drawConnectorPolyline(assembled.connectorPolylineCoords);
            }
        }

        const Live = window.TripWeather.Nav.PositionSource.Live;
        const isPlayback = this.positionSource && this.positionSource !== Live;
        if (isPlayback) {
            if (this.positionController) {
                this.positionController.stop();
                this.positionController = null;
            }
            const maneuverDistances = this.maneuvers.map(function(m) {
                return m.distanceFromStart;
            });
            const stopDistances = this.waypointStops
                .filter(function(s) { return !s.isFinal && s.duration > 0; })
                .map(function(s) { return s.distanceFromStart; });
            this.positionSource = window.TripWeather.Nav.PositionSource.fromUrl(
                assembled.activeGeometry,
                { maneuverDistances: maneuverDistances, stopDistances: stopDistances }
            );
            const self = this;
            this.positionController = this.positionSource.start(
                function(pos) { self._onPosition(pos); },
                function(err) { self._onPositionError(err); }
            );
        }

        this.rerouting = false;
    },

    stop: function() {
        if (!this.active) return;
        this.active = false;
        this.connectorActive = false;
        this.rerouting = false;
        this.offRouteSince = 0;
        this.arrivedAtFinal = false;
        this.pausedAtWaypoint = -1;

        if (this.positionController) {
            this.positionController.stop();
            this.positionController = null;
        }

        window.TripWeather.Nav.VoiceGuide.cancel();
        window.TripWeather.Nav.WakeLock.release();

        if (this.mapAdapter) {
            this.mapAdapter.exitNavMode();
        }
        this._exitNavUI();
        this.updateButtonState();
    },

    _onPosition: function(pos) {
        const C = window.TripWeather.Nav.Constants;
        const snap = window.TripWeather.Nav.RouteSnapper.snap(
            this.compiled, { lat: pos.lat, lng: pos.lng }, this.lastSegmentIdx);
        if (!snap) return;
        this.lastSegmentIdx = snap.segmentIdx;

        // Once the user is past the connector portion of the merged polyline,
        // their merged segment index maps back to a saved-polyline segment. Used
        // by off-route re-route to forward-snap onto the saved polyline.
        if (this._connectorLen <= 0) {
            this.lastSavedSegmentIdx = snap.segmentIdx;
        } else if (snap.segmentIdx >= this._connectorLen) {
            this.lastSavedSegmentIdx = snap.segmentIdx - this._connectorLen
                + this._savedSegIdxAtJoin + 1;
        }
        // Else still on connector — leave lastSavedSegmentIdx where the user
        // would join (set in _applyAssembled).

        const heading = Number.isFinite(pos.heading) ? pos.heading : null;
        this.mapAdapter.updateUserPosition(snap.snappedLat, snap.snappedLng, heading);
        this.mapAdapter.followCamera(snap.snappedLat, snap.snappedLng);

        // While paused at a waypoint (Phase 3d), or after the user has reached
        // the final destination, suppress voice prompts, off-route detection,
        // scheduler advances, and arrival checks. Map marker still updates so
        // the user can see their position.
        if (this.pausedAtWaypoint !== -1 || this.arrivedAtFinal) return;

        // Drop the dashed-orange connector once the user has reached the join
        // point (past the connector's distance AND on the saved polyline).
        if (this.connectorActive
            && snap.distanceAlongPolyline >= this.connectorEndDistance
            && snap.crossTrackM <= C.ON_ROUTE_THRESHOLD_M) {
            this.mapAdapter.clearConnectorPolyline();
            this.connectorActive = false;
        }

        // Off-route detection. A sustained crossTrack beyond OFF_ROUTE_THRESHOLD_M
        // for OFF_ROUTE_SUSTAINED_MS triggers a re-route. Cooldown prevents thrash
        // when the new route also goes off (e.g. user keeps driving away).
        const now = Date.now();
        if (snap.crossTrackM > C.OFF_ROUTE_THRESHOLD_M) {
            if (this.offRouteSince === 0) this.offRouteSince = now;
        } else {
            this.offRouteSince = 0;
        }
        if (this.offRouteSince !== 0
            && !this.rerouting
            && (now - this.offRouteSince) >= C.OFF_ROUTE_SUSTAINED_MS
            && (now - this.lastRerouteAt) >= C.REROUTE_COOLDOWN_MS) {
            this._triggerReroute({ lat: pos.lat, lng: pos.lng });
            return;
        }

        // Waypoint stops (Phase 3d/3e): approach prompts, arrival pause, drive-
        // past detection, Skip button visibility. Returns true if the tick
        // should short-circuit (arrival just paused us, drive-past triggered a
        // reroute, etc.).
        if (this._handleWaypointStops(snap)) return;

        // Final-destination approach prompts ("In 1 mile, your destination will
        // be on the right.") fire from a separate one-element scheduler so
        // they don't interfere with regular maneuver bucketing.
        if (this.finalApproachScheduler) {
            const fr = this.finalApproachScheduler.update(snap.distanceAlongPolyline);
            if (fr && fr.phrase) {
                window.TripWeather.Nav.VoiceGuide.say(fr.phrase);
            }
        }

        const result = this.scheduler.update(snap.distanceAlongPolyline);
        if (result.phrase) {
            window.TripWeather.Nav.VoiceGuide.say(result.phrase);
        }

        this._updateBanner(result.nextManeuver, result.distanceToNext, snap.distanceAlongPolyline);

        // Arrival at final destination — within ARRIVAL_RADIUS_M of the polyline end.
        const remaining = this.compiled.totalDistance - snap.distanceAlongPolyline;
        if (remaining <= C.ARRIVAL_RADIUS_M) {
            this._handleFinalArrival();
        }
    },

    /**
     * Final-destination arrival: latch the arrived state, announce, switch the
     * banner, and pause the simulator so the dot stops at the destination.
     * Voice + off-route + scheduler are gated off in _onPosition by
     * arrivedAtFinal. Session ends only when the user taps Exit Navigation.
     */
    _handleFinalArrival: function() {
        if (this.arrivedAtFinal) return;
        this.arrivedAtFinal = true;

        const VoiceGuide = window.TripWeather.Nav.VoiceGuide;
        VoiceGuide.cancel();
        VoiceGuide.say('You have arrived.');

        const routeData = window.TripWeather.Managers.Route.currentRoute;
        const finalWp = routeData && routeData.waypoints
            ? routeData.waypoints[routeData.waypoints.length - 1] : null;
        const name = (finalWp && finalWp.name) || 'your destination';
        this._showArrivedBanner(name);

        this._hideSkipButton();
        this.skipVisibleForWaypoint = -1;

        if (this.positionController && this.positionController.pause) {
            this.positionController.pause();
        }
    },

    /**
     * Per-tick waypoint-stop bookkeeping (Phase 3d/3e):
     *   1. Fire approach prompts (FAR/MID/NEAR) for the next eligible duration
     *      waypoint via the waypoint scheduler.
     *   2. Update Skip-button visibility — appears when the user first comes
     *      within SKIP_AVAILABLE_DISTANCE_M of an eligible duration waypoint
     *      and stays visible until tap or arrival.
     *   3. Trigger arrival (pause + Continue) when the user is within
     *      ARRIVAL_RADIUS_M.
     *   4. Trigger drive-past reroute when the user advances past the
     *      waypoint's polyline distance without arriving.
     *
     * Returns true to short-circuit the rest of _onPosition.
     */
    _handleWaypointStops: function(snap) {
        const C = window.TripWeather.Nav.Constants;
        if (!this.waypointStops || this.waypointStops.length === 0) return false;

        if (this.waypointScheduler) {
            const r = this.waypointScheduler.update(snap.distanceAlongPolyline);
            if (r && r.phrase) {
                window.TripWeather.Nav.VoiceGuide.say(r.phrase);
            }
        }

        let nextEligibleStop = null;
        let nextEligibleStopArrIdx = -1;
        for (let i = 0; i < this.waypointStops.length; i++) {
            const stop = this.waypointStops[i];
            if (stop.isFinal) continue;
            if (!stop.duration || stop.duration <= 0) continue;
            if (this.arrivedWaypoints[stop.waypointIdx]) continue;
            if (this.skippedWaypoints[stop.waypointIdx]) continue;

            const distToStop = stop.distanceFromStart - snap.distanceAlongPolyline;

            // Arrival within ARRIVAL_RADIUS_M (regardless of approach side).
            if (Math.abs(distToStop) <= C.ARRIVAL_RADIUS_M) {
                this._pauseAtWaypoint(i);
                return true;
            }

            // Geographic backstop. The merged polyline doesn't always pass
            // through the waypoint's planned location — most notably when the
            // connector to a duration waypoint ends at a road point Q offset
            // from the planned location, and the merged polyline jumps from Q
            // to the next saved-tail vertex without visiting the waypoint
            // exactly. The polyline-distance check can miss while the user dot
            // is nonetheless geographically very close. This catches that case.
            if (stop.waypointLat != null && stop.waypointLng != null) {
                const geoDist = window.TripWeather.Nav.RouteSnapper.haversine(
                    { lat: snap.snappedLat, lng: snap.snappedLng },
                    { lat: stop.waypointLat, lng: stop.waypointLng });
                if (geoDist <= C.ARRIVAL_RADIUS_M) {
                    this._pauseAtWaypoint(i);
                    return true;
                }
            }

            // Drive-past: user is past the waypoint without arriving.
            if (distToStop < -C.ARRIVAL_RADIUS_M) {
                if (this._handleDrivePast(i, snap)) return true;
                continue;
            }

            // First eligible waypoint still ahead (distToStop > ARRIVAL_RADIUS_M).
            if (nextEligibleStop === null) {
                nextEligibleStop = stop;
                nextEligibleStopArrIdx = i;
            }
        }

        // Skip button visibility — show when we're within SKIP_AVAILABLE_DISTANCE_M
        // of the next eligible stop. Once shown for a stop, it sticks (driven-past
        // → reroute → approaching again all keep it visible until tap or arrival).
        if (nextEligibleStop) {
            const distToNext = nextEligibleStop.distanceFromStart - snap.distanceAlongPolyline;
            if (this.skipVisibleForWaypoint === nextEligibleStop.waypointIdx) {
                // Already showing for this waypoint.
            } else if (distToNext <= C.SKIP_AVAILABLE_DISTANCE_M && distToNext > -C.ARRIVAL_RADIUS_M) {
                this.skipVisibleForWaypoint = nextEligibleStop.waypointIdx;
                this._showSkipButton(nextEligibleStop);
            }
        } else if (this.skipVisibleForWaypoint !== -1) {
            this.skipVisibleForWaypoint = -1;
            this._hideSkipButton();
        }

        return false;
    },

    /**
     * Drive-past handling: user advanced past a duration waypoint's polyline
     * position without entering ARRIVAL_RADIUS_M. Trigger a reroute targeting
     * the missed waypoint itself (3e). Off-route detection stays active during
     * the reroute back; the cooldown limits API churn if the user keeps driving.
     * Returns true if a reroute was triggered.
     */
    _handleDrivePast: function(stopArrIdx, snap) {
        const C = window.TripWeather.Nav.Constants;
        if (this.rerouting) return false;
        const now = Date.now();
        if ((now - this.lastRerouteAt) < C.REROUTE_COOLDOWN_MS) return false;

        const stop = this.waypointStops[stopArrIdx];
        const routeMgr = window.TripWeather.Managers.Route;
        const routeData = routeMgr ? routeMgr.currentRoute : null;
        if (!routeData || !routeData.waypoints) return false;
        const wpData = routeData.waypoints[stop.waypointIdx];
        if (!wpData) return false;

        // Use the saved-polyline location of the waypoint as the reroute target —
        // not the snapped point on the saved polyline.
        const stopLng = routeData.geometry[stop.polylineIdx][0];
        const stopLat = routeData.geometry[stop.polylineIdx][1];

        // Map adapter gives us the user's last reported lat/lng; pull from the
        // current snap's underlying position via the snap's snapped coordinates
        // would be wrong (they're projected). _triggerReroute needs raw GPS.
        // Fortunately we still have it via the position source; but here we
        // approximate from the snap (close enough for the ORS connector start).
        const currentPos = { lat: snap.snappedLat, lng: snap.snappedLng };
        this._triggerReroute(currentPos, {
            dest: { lat: stopLat, lng: stopLng },
            destIsPlannedStop: true,
            targetWaypointIdx: stop.waypointIdx,
            voicePhrase: 'Re-routing to ' + (stop.name || 'your stop') + '.'
        });
        return true;
    },

    /**
     * Pause navigation at a duration waypoint. Voice + off-route + scheduler
     * are suppressed (gate in _onPosition). Banner switches to "Stopped at
     * [name] — tap Continue when ready." The Skip button is hidden (the user
     * has arrived; nothing to skip).
     */
    _pauseAtWaypoint: function(stopArrIdx) {
        const stop = this.waypointStops[stopArrIdx];
        this.pausedAtWaypoint = stop.waypointIdx;
        this.arrivedWaypoints[stop.waypointIdx] = true;
        this._rebuildWaypointScheduler();
        this._hideSkipButton();
        this.skipVisibleForWaypoint = -1;
        this._showPausedBanner(stop);
        // Stop the simulator while paused so the dot doesn't run off (no-op for
        // live GPS, where the user is physically there and not moving).
        if (this.positionController && this.positionController.pause) {
            this.positionController.pause();
        }
        const VoiceGuide = window.TripWeather.Nav.VoiceGuide;
        VoiceGuide.cancel();
        VoiceGuide.say('You have arrived at ' + (stop.name || 'your stop') + '.');
    },

    /**
     * Resume from a paused waypoint. Voice + off-route + scheduler resume in
     * _onPosition. If the user has moved off the route while paused (parked
     * elsewhere, drove away), the next position tick will trip off-route
     * detection and the standard guide-back kicks in.
     */
    _continueFromWaypoint: function() {
        if (this.pausedAtWaypoint === -1) return;
        this.pausedAtWaypoint = -1;
        // Reset the off-route timer so the user has a fresh 10s window before
        // a stale paused-period crossTrack reading triggers a reroute.
        this.offRouteSince = 0;
        this._hidePausedBanner();
        if (this.positionController && this.positionController.resume) {
            this.positionController.resume();
        }
    },

    /**
     * Skip the currently-approachable waypoint. Mark it as skipped, rebuild
     * the waypoint scheduler so its approach prompts stop firing, and trigger
     * a guide-back to a forward point past the skipped waypoint via 3c's
     * machinery (with originalIdxFloor advanced to skip the waypoint).
     */
    _handleSkip: function() {
        const idx = this.skipVisibleForWaypoint;
        if (idx === -1) return;
        this.skippedWaypoints[idx] = true;
        this.skipVisibleForWaypoint = -1;
        this._hideSkipButton();
        this._rebuildWaypointScheduler();

        // Find the skipped waypoint's polyline index to advance the floor past it.
        const routeMgr = window.TripWeather.Managers.Route;
        const routeData = routeMgr ? routeMgr.currentRoute : null;
        if (!routeData) return;

        // Use the snap's last known point as the reroute origin. The reroute's
        // forward-only snap uses lastSavedSegmentIdx; bump it past the skipped
        // waypoint's saved-polyline index so the connector lands beyond it.
        const stop = (this.waypointStops || []).find(function(s) {
            return s.waypointIdx === idx;
        });
        if (stop && this._connectorLen > 0) {
            // Stop's polylineIdx is on the merged polyline; convert back to
            // saved if it's in the saved tail.
            if (stop.polylineIdx >= this._connectorLen) {
                const savedIdx = stop.polylineIdx - this._connectorLen
                    + this._savedSegIdxAtJoin + 1;
                this.lastSavedSegmentIdx = Math.max(this.lastSavedSegmentIdx, savedIdx + 1);
            }
        } else if (stop) {
            this.lastSavedSegmentIdx = Math.max(this.lastSavedSegmentIdx, stop.polylineIdx + 1);
        }

        // Use the user's snapped position as the reroute origin. We don't have
        // the raw GPS lat/lng here; the snapped point is close enough to the
        // user that ORS will route from it sensibly.
        const lastSnap = this._lastSnapForReroute();
        if (!lastSnap) return;
        this._triggerReroute(
            { lat: lastSnap.lat, lng: lastSnap.lng },
            { voicePhrase: 'Skipping. Re-routing.' });
    },

    _lastSnapForReroute: function() {
        // The user's most recent snapped position (set by _onPosition via the
        // map adapter's marker). Pull from the marker if available; this avoids
        // having to thread a raw position through the Skip click handler.
        const adapter = this.mapAdapter;
        if (adapter && adapter.navUserMarker) {
            const ll = adapter.navUserMarker.getLatLng();
            return { lat: ll.lat, lng: ll.lng };
        }
        return null;
    },

    _onPositionError: function(err) {
        console.warn('Navigation: position fix failed:',
            err && err.message, 'code=', err && err.code);
        // Lead with "navigation needs GPS" framing because the user is mid-trip
        // and aborting; describeError adds the specific cause so they can fix it.
        const diag = window.TripWeather.Utils.GeolocationDiagnostics;
        window.TripWeather.Managers.UI.showToast(
            'Navigation stopped — ' + diag.describeError(err), 'error');
        this.stop();
    },

    /**
     * Flatten segments[].steps[] into a single ordered maneuver list with
     * cumulative distance-from-start of each maneuver point along the polyline.
     * ORS step.way_points are global indexes into the source routeData.geometry.
     *
     * Type-10 (goal/arrive) and type-11 (depart) steps are filtered out — they
     * mark waypoint positions, not driving actions. Final-destination arrival is
     * handled by the polyline-end check in _onPosition; intermediate waypoint
     * announcements (for duration > 0 stops) come from waypointStops[] and the
     * Phase 3d pause logic, not from this list.
     *
     * indexShift / originalIdxFloor / originalIdxCeil support connector splicing:
     *   - Connector contribution: shift=0, floor=-1 (keep all connector
     *     instructions — visual trim doesn't drop data; see _assembleMergedRoute).
     *   - Saved-route contribution: shift = M - qSegIdx - 1, floor = qSegIdx
     *     (skip saved-route steps already passed/owned by the connector).
     *   - No-connector case: shift=0, floor=-1.
     * originalIdxCeil is left in the signature as an opt-in cap for callers that
     * need to drop steps past a specific source-polyline index.
     */
    _flattenManeuvers: function(routeData, compiled, indexShift, originalIdxFloor, originalIdxCeil) {
        indexShift = indexShift || 0;
        originalIdxFloor = (originalIdxFloor == null) ? -1 : originalIdxFloor;
        originalIdxCeil = (originalIdxCeil == null) ? Infinity : originalIdxCeil;
        const maneuvers = [];
        if (!routeData.segments) return maneuvers;
        for (let s = 0; s < routeData.segments.length; s++) {
            const segment = routeData.segments[s];
            if (!segment.steps) continue;
            for (let k = 0; k < segment.steps.length; k++) {
                const step = segment.steps[k];
                if (step.type === this.ORS_STEP_TYPE_GOAL
                    || step.type === this.ORS_STEP_TYPE_DEPART) {
                    continue;
                }
                const wp = step.wayPoints || step.way_points;
                if (!wp || wp.length === 0) continue;
                const oldIdx = wp[0];
                if (oldIdx <= originalIdxFloor) continue;
                if (oldIdx > originalIdxCeil) continue;
                const polylineIdx = oldIdx + indexShift;
                if (polylineIdx >= compiled.cumDist.length) continue;
                maneuvers.push({
                    distanceFromStart: compiled.cumDist[polylineIdx],
                    distance: step.distance || 0,
                    duration: step.duration || 0,
                    type: step.type,
                    instruction: step.instruction || '',
                    name: step.name || '',
                    polylineIdx: polylineIdx
                });
            }
        }
        return maneuvers;
    },

    /**
     * Build a parallel structure mapping each planned waypoint to its position on
     * the polyline. Phase 3d uses this to drive arrival announcements + pause for
     * duration > 0 waypoints; duration == 0 waypoints stay silent passthroughs.
     *
     * Polyline indexes come from the (otherwise filtered) ORS depart/goal markers:
     *   waypoint 0          → wayPoints[0] of segments[0].steps[0]   (depart)
     *   waypoint i (i > 0)  → last wayPoint of last step of segments[i-1] (goal)
     *
     * indexShift / originalIdxFloor mirror _flattenManeuvers and let this work
     * over a merged geometry: with a connector in front, waypoints at or before
     * the join point are skipped (the user effectively starts mid-trip) and the
     * rest get their saved-polyline indices remapped onto the merged polyline.
     */
    _buildWaypointStops: function(routeData, compiled, indexShift, originalIdxFloor) {
        indexShift = indexShift || 0;
        originalIdxFloor = (originalIdxFloor == null) ? -1 : originalIdxFloor;
        const stops = [];
        if (!routeData.waypoints || !routeData.segments) return stops;

        const waypoints = routeData.waypoints;
        const segments = routeData.segments;
        const isFinal = function(idx) { return idx === waypoints.length - 1; };

        for (let i = 0; i < waypoints.length; i++) {
            let oldIdx;
            if (i === 0) {
                const firstSeg = segments[0];
                const firstStep = firstSeg && firstSeg.steps && firstSeg.steps[0];
                const wp = firstStep && (firstStep.wayPoints || firstStep.way_points);
                oldIdx = wp && wp.length > 0 ? wp[0] : 0;
            } else {
                const seg = segments[i - 1];
                if (!seg || !seg.steps || seg.steps.length === 0) continue;
                const lastStep = seg.steps[seg.steps.length - 1];
                const wp = lastStep.wayPoints || lastStep.way_points;
                oldIdx = wp && wp.length > 0 ? wp[wp.length - 1] : (routeData.geometry.length - 1);
            }
            if (oldIdx <= originalIdxFloor) continue;
            const polylineIdx = oldIdx + indexShift;
            if (polylineIdx >= compiled.cumDist.length) continue;
            // waypointLat/Lng carries the planned waypoint location for the
            // geographic arrival backstop (see _handleWaypointStops). For non-
            // synthetic stops, this is the saved-polyline vertex at oldIdx.
            const sourceVertex = routeData.geometry[oldIdx];
            stops.push({
                waypointIdx: i,
                polylineIdx: polylineIdx,
                distanceFromStart: compiled.cumDist[polylineIdx],
                duration: waypoints[i].duration || 0,
                name: waypoints[i].name || ('Waypoint ' + (i + 1)),
                waypointLat: sourceVertex ? sourceVertex[1] : null,
                waypointLng: sourceVertex ? sourceVertex[0] : null,
                isFinal: isFinal(i)
            });
        }
        return stops;
    },

    _enterNavUI: function() {
        document.body.classList.add('nav-mode');

        if (!this.bannerEl) {
            const banner = document.createElement('div');
            banner.className = 'nav-banner';
            banner.id = 'nav-banner';

            const instr = document.createElement('div');
            instr.className = 'nav-instruction';
            instr.id = 'nav-instruction';
            instr.textContent = 'Starting navigation…';

            const dist = document.createElement('div');
            dist.className = 'nav-distance';
            dist.id = 'nav-distance';

            banner.appendChild(dist);
            banner.appendChild(instr);
            document.body.appendChild(banner);
            this.bannerEl = banner;
        }
        this.bannerEl.style.display = 'block';

        if (!this.exitBtnEl) {
            const exitBtn = document.createElement('button');
            exitBtn.type = 'button';
            exitBtn.className = 'nav-exit-btn';
            exitBtn.id = 'nav-exit-btn';
            exitBtn.textContent = 'Exit Navigation';
            exitBtn.addEventListener('click', this.stop.bind(this));
            document.body.appendChild(exitBtn);
            this.exitBtnEl = exitBtn;
        }
        this.exitBtnEl.style.display = 'block';

        if (!this.continueBtnEl) {
            const cont = document.createElement('button');
            cont.type = 'button';
            cont.className = 'nav-continue-btn';
            cont.id = 'nav-continue-btn';
            cont.textContent = 'Continue';
            cont.addEventListener('click', this._continueFromWaypoint.bind(this));
            document.body.appendChild(cont);
            this.continueBtnEl = cont;
        }
        this.continueBtnEl.style.display = 'none';

        if (!this.skipBtnEl) {
            const skip = document.createElement('button');
            skip.type = 'button';
            skip.className = 'nav-skip-btn';
            skip.id = 'nav-skip-btn';
            skip.textContent = 'Skip';
            skip.addEventListener('click', this._handleSkip.bind(this));
            document.body.appendChild(skip);
            this.skipBtnEl = skip;
        }
        this.skipBtnEl.style.display = 'none';

        // The map div needs to redraw at its new (full-screen) size — Leaflet
        // doesn't notice CSS-driven container size changes on its own.
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            setTimeout(function() { map.invalidateSize(); }, 50);
        }
    },

    _exitNavUI: function() {
        document.body.classList.remove('nav-mode');
        if (this.bannerEl) {
            this.bannerEl.classList.remove('nav-banner-paused');
            this.bannerEl.classList.remove('nav-banner-arrived');
            this.bannerEl.style.display = 'none';
        }
        if (this.exitBtnEl) this.exitBtnEl.style.display = 'none';
        if (this.continueBtnEl) this.continueBtnEl.style.display = 'none';
        if (this.skipBtnEl) this.skipBtnEl.style.display = 'none';

        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            setTimeout(function() { map.invalidateSize(); }, 50);
        }
    },

    _updateBanner: function(nextManeuver, distanceToNext, distanceAlongPolyline) {
        // Don't overwrite the paused or arrived banner with maneuver text —
        // those banners are shown by _showPausedBanner / _showArrivedBanner
        // and stay until Continue / Exit Navigation.
        if (this.pausedAtWaypoint !== -1) return;
        if (this.arrivedAtFinal) return;

        const instrEl = document.getElementById('nav-instruction');
        const distEl = document.getElementById('nav-distance');
        if (!instrEl || !distEl) return;

        if (!nextManeuver) {
            instrEl.textContent = 'Arriving at destination';
            distEl.textContent = '';
            return;
        }
        instrEl.textContent = nextManeuver.instruction
            + (nextManeuver.name ? ' (' + nextManeuver.name + ')' : '');
        distEl.textContent = window.TripWeather.Nav.ManeuverScheduler.formatDistance(distanceToNext);
    },

    _showPausedBanner: function(stop) {
        if (!this.bannerEl) return;
        this.bannerEl.classList.add('nav-banner-paused');
        const instrEl = document.getElementById('nav-instruction');
        const distEl = document.getElementById('nav-distance');
        if (instrEl) {
            instrEl.textContent = 'Stopped at ' + (stop.name || 'your stop')
                + ' — tap Continue when ready.';
        }
        if (distEl) distEl.textContent = '';
        if (this.continueBtnEl) this.continueBtnEl.style.display = 'block';
    },

    _hidePausedBanner: function() {
        if (this.bannerEl) this.bannerEl.classList.remove('nav-banner-paused');
        if (this.continueBtnEl) this.continueBtnEl.style.display = 'none';
    },

    _showArrivedBanner: function(name) {
        if (!this.bannerEl) return;
        this.bannerEl.classList.add('nav-banner-arrived');
        const instrEl = document.getElementById('nav-instruction');
        const distEl = document.getElementById('nav-distance');
        if (instrEl) instrEl.textContent = 'You have arrived at ' + name;
        if (distEl) distEl.textContent = '';
    },

    _showSkipButton: function(stop) {
        if (!this.skipBtnEl) return;
        this.skipBtnEl.textContent = 'Skip ' + (stop.name || 'stop');
        this.skipBtnEl.style.display = 'block';
    },

    _hideSkipButton: function() {
        if (this.skipBtnEl) this.skipBtnEl.style.display = 'none';
    }
};
