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
    compiled: null,
    maneuvers: [],
    waypointStops: [],
    scheduler: null,
    positionSource: null,
    stopPositionSource: null,
    mapAdapter: null,
    lastSegmentIdx: 0,
    bannerEl: null,
    exitBtnEl: null,

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
        this.updateButtonState();
    },

    /**
     * Show or hide the Navigate button based on whether a route is currently displayed.
     * Called by RouteManager after a route is calculated or cleared.
     */
    updateButtonState: function() {
        const navBtn = document.getElementById('navigate-btn');
        if (!navBtn) return;
        const routeMgr = window.TripWeather.Managers.Route;
        const hasRoute = routeMgr
            && routeMgr.currentRoute
            && routeMgr.currentRoute.geometry
            && routeMgr.currentRoute.geometry.length >= 2;
        navBtn.disabled = !hasRoute;
    },

    start: function() {
        if (this.active) return;

        const routeMgr = window.TripWeather.Managers.Route;
        const routeData = routeMgr ? routeMgr.currentRoute : null;
        if (!routeData || !routeData.geometry || routeData.geometry.length < 2) {
            window.TripWeather.Managers.UI.showToast(
                'Calculate or load a route before starting navigation.', 'warning');
            return;
        }

        // The Navigate click is a user gesture — use it to unlock TTS for the session.
        // iOS Safari otherwise rejects the first speak() call.
        window.TripWeather.Nav.VoiceGuide.unlock();

        this.compiled = window.TripWeather.Nav.RouteSnapper.compile(routeData.geometry);
        this.maneuvers = this._flattenManeuvers(routeData, this.compiled);
        this.waypointStops = this._buildWaypointStops(routeData, this.compiled);

        this.scheduler = window.TripWeather.Nav.ManeuverScheduler.create(this.maneuvers);
        this.lastSegmentIdx = 0;

        this.mapAdapter = window.TripWeather.Nav.MapAdapter.Leaflet;
        this.mapAdapter.initialize(window.TripWeather.Managers.Map.getMap());
        this.mapAdapter.enterNavMode();

        this._enterNavUI();

        window.TripWeather.Nav.WakeLock.request();

        const maneuverDistances = this.maneuvers.map(function(m) { return m.distanceFromStart; });
        this.positionSource = window.TripWeather.Nav.PositionSource.fromUrl(
            routeData.geometry,
            { maneuverDistances: maneuverDistances }
        );
        const self = this;
        this.stopPositionSource = this.positionSource.start(
            function(pos) { self._onPosition(pos); },
            function(err) { self._onPositionError(err); }
        );

        this.active = true;
        window.TripWeather.Nav.VoiceGuide.say('Starting navigation.');
    },

    stop: function() {
        if (!this.active) return;
        this.active = false;

        if (this.stopPositionSource) {
            this.stopPositionSource();
            this.stopPositionSource = null;
        }

        window.TripWeather.Nav.VoiceGuide.cancel();
        window.TripWeather.Nav.WakeLock.release();

        if (this.mapAdapter) {
            this.mapAdapter.exitNavMode();
        }
        this._exitNavUI();
    },

    _onPosition: function(pos) {
        const snap = window.TripWeather.Nav.RouteSnapper.snap(
            this.compiled, { lat: pos.lat, lng: pos.lng }, this.lastSegmentIdx);
        if (!snap) return;
        this.lastSegmentIdx = snap.segmentIdx;

        const heading = Number.isFinite(pos.heading) ? pos.heading : null;
        this.mapAdapter.updateUserPosition(snap.snappedLat, snap.snappedLng, heading);
        this.mapAdapter.followCamera(snap.snappedLat, snap.snappedLng);

        const result = this.scheduler.update(snap.distanceAlongPolyline);
        if (result.phrase) {
            window.TripWeather.Nav.VoiceGuide.say(result.phrase);
        }

        this._updateBanner(result.nextManeuver, result.distanceToNext, snap.distanceAlongPolyline);

        // Arrival at final destination — within ARRIVAL_RADIUS_M of the polyline end.
        const remaining = this.compiled.totalDistance - snap.distanceAlongPolyline;
        if (remaining <= window.TripWeather.Nav.Constants.ARRIVAL_RADIUS_M) {
            window.TripWeather.Nav.VoiceGuide.say('You have arrived at your destination.');
            const self = this;
            // Defer the actual stop slightly so the utterance has time to start.
            setTimeout(function() { self.stop(); }, 1500);
        }
    },

    _onPositionError: function(err) {
        console.warn('Navigation: position fix failed:', err && err.message);
        window.TripWeather.Managers.UI.showToast(
            'Location unavailable. Navigation needs precise GPS to continue.', 'error');
        this.stop();
    },

    /**
     * Flatten segments[].steps[] into a single ordered maneuver list with
     * cumulative distance-from-start of each maneuver point along the polyline.
     * ORS step.way_points are global indexes into routeData.geometry.
     *
     * Type-10 (goal/arrive) and type-11 (depart) steps are filtered out — they
     * mark waypoint positions, not driving actions. Final-destination arrival is
     * handled by the polyline-end check in _onPosition; intermediate waypoint
     * announcements (for duration > 0 stops) come from waypointStops[] and the
     * Phase 3d pause logic, not from this list.
     */
    _flattenManeuvers: function(routeData, compiled) {
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
                const polylineIdx = wp[0];
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
     */
    _buildWaypointStops: function(routeData, compiled) {
        const stops = [];
        if (!routeData.waypoints || !routeData.segments) return stops;

        const waypoints = routeData.waypoints;
        const segments = routeData.segments;
        const isFinal = function(idx) { return idx === waypoints.length - 1; };

        for (let i = 0; i < waypoints.length; i++) {
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
                polylineIdx = wp && wp.length > 0 ? wp[wp.length - 1] : compiled.points.length - 1;
            }
            if (polylineIdx >= compiled.cumDist.length) continue;
            stops.push({
                waypointIdx: i,
                polylineIdx: polylineIdx,
                distanceFromStart: compiled.cumDist[polylineIdx],
                duration: waypoints[i].duration || 0,
                name: waypoints[i].name || ('Waypoint ' + (i + 1)),
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

        // The map div needs to redraw at its new (full-screen) size — Leaflet
        // doesn't notice CSS-driven container size changes on its own.
        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            setTimeout(function() { map.invalidateSize(); }, 50);
        }
    },

    _exitNavUI: function() {
        document.body.classList.remove('nav-mode');
        if (this.bannerEl) this.bannerEl.style.display = 'none';
        if (this.exitBtnEl) this.exitBtnEl.style.display = 'none';

        const map = window.TripWeather.Managers.Map.getMap();
        if (map) {
            setTimeout(function() { map.invalidateSize(); }, 50);
        }
    },

    _updateBanner: function(nextManeuver, distanceToNext, distanceAlongPolyline) {
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
    }
};
