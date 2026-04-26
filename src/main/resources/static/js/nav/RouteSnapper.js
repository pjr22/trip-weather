/**
 * Route Snapper
 * Projects a GPS position onto a route polyline and reports where it landed.
 *
 * Use compile(geometry) once per route to precompute cumulative distances; then
 * call snap(compiled, position, hintIdx) per fix.
 *
 * Projection uses local equirectangular math anchored at each segment's start
 * vertex — accurate to centimetres at the cross-track distances we care about
 * (a few hundred metres at most). hintIdx biases the search to a forward window
 * around the previously matched segment so loops/hairpins don't snap backwards.
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};

(function() {

    const EARTH_RADIUS_M = 6371000;
    const DEG_TO_RAD = Math.PI / 180;
    const M_PER_DEG_LAT = 111320;

    function haversine(a, b) {
        const dLat = (b.lat - a.lat) * DEG_TO_RAD;
        const dLng = (b.lng - a.lng) * DEG_TO_RAD;
        const sinLat = Math.sin(dLat / 2);
        const sinLng = Math.sin(dLng / 2);
        const v = sinLat * sinLat
            + Math.cos(a.lat * DEG_TO_RAD) * Math.cos(b.lat * DEG_TO_RAD) * sinLng * sinLng;
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(v), Math.sqrt(1 - v));
    }

    function projectOntoSegment(a, b, p) {
        const cosLat = Math.cos(a.lat * DEG_TO_RAD);
        const mPerDegLng = M_PER_DEG_LAT * cosLat;

        const bx = (b.lng - a.lng) * mPerDegLng;
        const by = (b.lat - a.lat) * M_PER_DEG_LAT;
        const px = (p.lng - a.lng) * mPerDegLng;
        const py = (p.lat - a.lat) * M_PER_DEG_LAT;

        const segLenSq = bx * bx + by * by;
        let t = segLenSq > 0 ? (px * bx + py * by) / segLenSq : 0;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;

        const sx = t * bx;
        const sy = t * by;
        const dx = px - sx;
        const dy = py - sy;

        return {
            snappedLat: a.lat + sy / M_PER_DEG_LAT,
            snappedLng: a.lng + sx / mPerDegLng,
            crossTrackM: Math.sqrt(dx * dx + dy * dy),
            fraction: t
        };
    }

    function compile(geometry) {
        const points = [];
        const cumDist = [0];
        if (!geometry || geometry.length === 0) {
            return { points: points, cumDist: cumDist, totalDistance: 0 };
        }
        for (let i = 0; i < geometry.length; i++) {
            points.push({ lat: geometry[i][1], lng: geometry[i][0] });
            if (i > 0) {
                cumDist.push(cumDist[i - 1] + haversine(points[i - 1], points[i]));
            }
        }
        return {
            points: points,
            cumDist: cumDist,
            totalDistance: cumDist[cumDist.length - 1]
        };
    }

    function snapWithinRange(compiled, position, startIdx, endIdx) {
        let best = null;
        for (let i = startIdx; i < endIdx; i++) {
            const r = projectOntoSegment(compiled.points[i], compiled.points[i + 1], position);
            if (!best || r.crossTrackM < best.crossTrackM) {
                const segLen = compiled.cumDist[i + 1] - compiled.cumDist[i];
                best = {
                    snappedLat: r.snappedLat,
                    snappedLng: r.snappedLng,
                    crossTrackM: r.crossTrackM,
                    fraction: r.fraction,
                    segmentIdx: i,
                    distanceAlongPolyline: compiled.cumDist[i] + r.fraction * segLen
                };
            }
        }
        return best;
    }

    function snap(compiled, position, hintIdx) {
        const C = window.TripWeather.Nav.Constants;
        const points = compiled.points;
        if (points.length < 2) return null;

        const hint = (hintIdx == null) ? 0 : hintIdx;
        const start = Math.max(0, hint - 5);
        const end = Math.min(points.length - 1, hint + C.SNAP_FORWARD_WINDOW_SEGMENTS);

        let best = snapWithinRange(compiled, position, start, end);

        // Fall back to a full scan if the windowed match looks bad — covers the
        // case where the user has jumped well outside the forward window (e.g.
        // resuming nav after a long pause, or testing with simulated GPS).
        if (!best || best.crossTrackM > 200) {
            const full = snapWithinRange(compiled, position, 0, points.length - 1);
            if (full && (!best || full.crossTrackM < best.crossTrackM)) {
                best = full;
            }
        }

        return best;
    }

    window.TripWeather.Nav.RouteSnapper = {
        compile: compile,
        snap: snap,
        haversine: haversine
    };

})();
