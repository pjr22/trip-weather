/**
 * Navigation Constants
 * Single source of truth for distances, intervals, and thresholds used across the
 * navigation engine. Centralised so a future Settings dialog can wire them up
 * (units, voice, language) without restructuring.
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};

window.TripWeather.Nav.Constants = {

    // Unit conversions
    METERS_PER_MILE: 1609.344,
    METERS_PER_FOOT: 0.3048,

    // Geolocation
    POSITION_MAX_AGE_MS: 1000,
    POSITION_TIMEOUT_MS: 30000,

    // Snap to route — search window forward of the last matched index, in polyline
    // segments. Bounds the per-fix work and avoids matching a closer earlier point
    // when the route doubles back near itself.
    SNAP_FORWARD_WINDOW_SEGMENTS: 200,

    // Off-route detection (Phase 3 — declared here so tuning happens in one place)
    OFF_ROUTE_THRESHOLD_M: 50,
    OFF_ROUTE_SUSTAINED_MS: 10000,
    REROUTE_COOLDOWN_MS: 20000,
    ON_ROUTE_THRESHOLD_M: 30,

    // Voice prompt buckets — distance from snapped position to the next maneuver.
    // Each bucket fires once per maneuver as the user crosses the threshold.
    BUCKET_FAR_M: 1609.344,           // 1 mile
    BUCKET_MID_M: 1609.344 * 0.25,    // 0.25 mile
    BUCKET_NEAR_M: 152.4,             // 500 ft
    BUCKET_NOW_M: 30,

    // Collapse the "Far" prompt when the previous maneuver ends within this distance
    // of the next ("turn right, then immediately turn left" reads better than two
    // far-distance prompts back to back).
    BUCKET_BUNCHING_M: 400,

    // Waypoint behaviour (Phase 3)
    ARRIVAL_RADIUS_M: 50,
    SKIP_AVAILABLE_DISTANCE_M: 1609.344 * 2,   // 2 miles

    // Camera / display
    NAV_FOLLOW_ZOOM: 17,

    // Simulator (?simgps=1)
    SIM_DEFAULT_SPEED_MPS: 25,            // ~90 km/h ground speed
    SIM_DEFAULT_SPEED_MULTIPLIER: 5,
    SIM_EMIT_INTERVAL_MS: 500,

    // When the next maneuver is closer than these thresholds, the simulator
    // overrides the URL simspeed to the corresponding multiplier so the user
    // can hear voice prompts at a realistic pace before quick turns.
    SIM_SLOWDOWN_FAR_M: 1609.344,         // < 1 mile  → multiplier 1
    SIM_SLOWDOWN_NEAR_M: 1609.344 * 0.25, // < 0.25 mi → multiplier 0.5
    SIM_SLOWDOWN_FAR_MULTIPLIER: 1,
    SIM_SLOWDOWN_NEAR_MULTIPLIER: 0.5,

    // Voice
    VOICE_LANG: 'en-US',
    VOICE_RATE: 1.0
};
