/**
 * Position Source
 * Abstraction over the source of position fixes during navigation.
 *
 * Two implementations:
 *   - Live: navigator.geolocation.watchPosition with high accuracy.
 *   - Playback: walks a route polyline at a configurable speed, behind ?simgps=1.
 *     Lets us test the navigation engine end-to-end without driving.
 *
 * Both expose: start(onPosition, onError) → stopFn
 *
 * onPosition receives a normalised fix:
 *   { lat, lng, accuracy, heading, speed, timestamp }
 * heading and speed may be null for live fixes (browser doesn't always provide them).
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};

(function() {

    const EARTH_RADIUS_M = 6371000;
    const DEG_TO_RAD = Math.PI / 180;
    const RAD_TO_DEG = 180 / Math.PI;

    function haversine(lat1, lng1, lat2, lng2) {
        const dLat = (lat2 - lat1) * DEG_TO_RAD;
        const dLng = (lng2 - lng1) * DEG_TO_RAD;
        const a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1 * DEG_TO_RAD) * Math.cos(lat2 * DEG_TO_RAD)
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    function bearing(lat1, lng1, lat2, lng2) {
        const lat1r = lat1 * DEG_TO_RAD;
        const lat2r = lat2 * DEG_TO_RAD;
        const dLng = (lng2 - lng1) * DEG_TO_RAD;
        const y = Math.sin(dLng) * Math.cos(lat2r);
        const x = Math.cos(lat1r) * Math.sin(lat2r) - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLng);
        const brng = Math.atan2(y, x) * RAD_TO_DEG;
        return (brng + 360) % 360;
    }

    /**
     * Controller contract returned by source.start():
     *   { stop(), pause(), resume() }
     * pause/resume are real on the playback source (clears/restarts the emit
     * timer) and no-ops on Live (the GPS keeps streaming regardless).
     */
    const Live = {
        start: function(onPosition, onError) {
            if (!('geolocation' in navigator)) {
                onError && onError({ code: 0, message: 'Geolocation not supported' });
                return { stop: function() {}, pause: function() {}, resume: function() {} };
            }
            const C = window.TripWeather.Nav.Constants;
            const watchId = navigator.geolocation.watchPosition(
                function(pos) {
                    onPosition({
                        lat: pos.coords.latitude,
                        lng: pos.coords.longitude,
                        accuracy: pos.coords.accuracy,
                        heading: Number.isFinite(pos.coords.heading) ? pos.coords.heading : null,
                        speed: Number.isFinite(pos.coords.speed) ? pos.coords.speed : null,
                        timestamp: pos.timestamp
                    });
                },
                function(err) {
                    onError && onError(err);
                },
                {
                    enableHighAccuracy: true,
                    maximumAge: C.POSITION_MAX_AGE_MS,
                    timeout: C.POSITION_TIMEOUT_MS
                }
            );
            return {
                stop: function() { navigator.geolocation.clearWatch(watchId); },
                pause: function() {},   // user is physically there, no virtual pause
                resume: function() {}
            };
        }
    };

    /**
     * Playback source — walks a route polyline at a configurable ground speed,
     * emitting positions every emitInterval ms.
     *
     * geometry: [[lng, lat, ele?], ...] (ORS / GeoJSON ordering, matches RouteData.geometry)
     *
     * options.maneuverDistances (optional) — array of cumulative-distance values
     * (metres along the polyline) for each maneuver point. When supplied, the
     * playback slows down to a realistic pace as it approaches a maneuver so the
     * tester can hear the voice prompts before quick turns.
     */
    function makePlaybackFromGeometry(geometry, options) {
        options = options || {};
        const C = window.TripWeather.Nav.Constants;
        const baseSpeedMps = options.speedMps || C.SIM_DEFAULT_SPEED_MPS;
        const baseMultiplier = options.speedMultiplier || C.SIM_DEFAULT_SPEED_MULTIPLIER;
        // Combined "slow down before" points: turn maneuvers AND duration
        // waypoint stops. Without the stops, the simulator can step past the
        // ARRIVAL_RADIUS_M window in one tick at high simspeed values.
        const maneuverDistances = options.maneuverDistances || [];
        const stopDistances = options.stopDistances || [];
        const slowdownDistances = maneuverDistances.concat(stopDistances)
            .sort(function(a, b) { return a - b; });
        const emitInterval = options.emitInterval || C.SIM_EMIT_INTERVAL_MS;

        function multiplierFor(distanceAlong) {
            if (slowdownDistances.length === 0) return baseMultiplier;
            // Find the next slowdown point still ahead of us (with a small fudge
            // so we don't latch onto the point we're sitting exactly on top of).
            let nextDist = null;
            for (let i = 0; i < slowdownDistances.length; i++) {
                if (slowdownDistances[i] > distanceAlong + 5) {
                    nextDist = slowdownDistances[i];
                    break;
                }
            }
            if (nextDist == null) return baseMultiplier;
            const distToNext = nextDist - distanceAlong;
            if (distToNext < C.SIM_SLOWDOWN_NEAR_M) return C.SIM_SLOWDOWN_NEAR_MULTIPLIER;
            if (distToNext < C.SIM_SLOWDOWN_FAR_M) return C.SIM_SLOWDOWN_FAR_MULTIPLIER;
            return baseMultiplier;
        }

        // Pre-compute cumulative distance at each polyline vertex so the emit loop
        // can locate the current position with a simple forward scan.
        const points = [];
        const cumDist = [0];
        for (let i = 0; i < geometry.length; i++) {
            points.push({ lat: geometry[i][1], lng: geometry[i][0] });
            if (i > 0) {
                cumDist.push(cumDist[i - 1]
                    + haversine(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng));
            }
        }
        const totalDistance = cumDist[cumDist.length - 1];

        return {
            start: function(onPosition, onError) {
                if (points.length < 2) {
                    onError && onError({ code: 0, message: 'Not enough geometry for playback' });
                    return { stop: function() {}, pause: function() {}, resume: function() {} };
                }

                let distanceAlong = 0;
                let segmentIdx = 0;
                let intervalId = null;
                let stopped = false;

                function tick() {
                    const mult = multiplierFor(distanceAlong);
                    const speedMps = baseSpeedMps * mult;
                    distanceAlong += speedMps * (emitInterval / 1000);
                    if (distanceAlong >= totalDistance) {
                        clearInterval(intervalId);
                        intervalId = null;
                        // Final emit at the end of the route.
                        const last = points[points.length - 1];
                        const prev = points[points.length - 2];
                        onPosition({
                            lat: last.lat,
                            lng: last.lng,
                            accuracy: 5,
                            heading: bearing(prev.lat, prev.lng, last.lat, last.lng),
                            speed: 0,
                            timestamp: Date.now()
                        });
                        return;
                    }

                    while (segmentIdx < points.length - 1 && cumDist[segmentIdx + 1] < distanceAlong) {
                        segmentIdx++;
                    }
                    const segLen = cumDist[segmentIdx + 1] - cumDist[segmentIdx];
                    const fraction = segLen > 0 ? (distanceAlong - cumDist[segmentIdx]) / segLen : 0;
                    const a = points[segmentIdx];
                    const b = points[segmentIdx + 1];
                    onPosition({
                        lat: a.lat + fraction * (b.lat - a.lat),
                        lng: a.lng + fraction * (b.lng - a.lng),
                        accuracy: 5,
                        heading: bearing(a.lat, a.lng, b.lat, b.lng),
                        speed: speedMps,
                        timestamp: Date.now()
                    });
                }

                intervalId = setInterval(tick, emitInterval);

                return {
                    stop: function() {
                        stopped = true;
                        if (intervalId !== null) {
                            clearInterval(intervalId);
                            intervalId = null;
                        }
                    },
                    pause: function() {
                        if (stopped) return;
                        if (intervalId !== null) {
                            clearInterval(intervalId);
                            intervalId = null;
                        }
                    },
                    resume: function() {
                        if (stopped) return;
                        if (intervalId === null) {
                            intervalId = setInterval(tick, emitInterval);
                        }
                    }
                };
            }
        };
    }

    /**
     * Choose a position source based on URL query params.
     *   ?simgps=1                  → playback along the supplied route geometry
     *   ?simgps=1&simspeed=10      → 10× ground speed (overridden near maneuvers
     *                                 and waypoint stops)
     *
     * options.maneuverDistances and options.stopDistances are both forwarded to
     * the playback source so it slows down approaching either kind of point.
     */
    function fromUrl(routeGeometry, options) {
        options = options || {};
        const params = new URLSearchParams(window.location.search);
        if (params.get('simgps') === '1' && routeGeometry && routeGeometry.length >= 2) {
            const speedMultiplier = parseFloat(params.get('simspeed'))
                || window.TripWeather.Nav.Constants.SIM_DEFAULT_SPEED_MULTIPLIER;
            console.log('Navigation: using simulated GPS playback at ' + speedMultiplier + '× speed');
            return makePlaybackFromGeometry(routeGeometry, {
                speedMultiplier: speedMultiplier,
                maneuverDistances: options.maneuverDistances,
                stopDistances: options.stopDistances
            });
        }
        return Live;
    }

    window.TripWeather.Nav.PositionSource = {
        Live: Live,
        makePlaybackFromGeometry: makePlaybackFromGeometry,
        fromUrl: fromUrl
    };

})();
