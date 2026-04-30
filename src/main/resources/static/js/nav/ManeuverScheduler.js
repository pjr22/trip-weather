/**
 * Maneuver Scheduler
 * Tracks the user's progress through a flat list of maneuvers and decides when
 * to fire voice prompts. Each maneuver has four prompt buckets (FAR, MID, NEAR,
 * NOW); each bucket fires at most once per maneuver as the distance-to-next
 * crosses the bucket threshold.
 *
 * Bunching: if the previous maneuver ends within BUCKET_BUNCHING_M of the next
 * one, the FAR prompt for the next maneuver is suppressed so the user doesn't
 * hear "In 1 mile..." right after "Turn right". Combining bunched prompts into
 * "...then immediately..." phrasing is deferred.
 *
 * Maneuver shape (constructed by NavigationManager from segments[].steps[]):
 *   { distanceFromStart, distance, duration, type, instruction, name, polylineIdx }
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};

(function() {

    function formatDistance(meters) {
        const C = window.TripWeather.Nav.Constants;
        if (meters < 304.8) {
            const ft = Math.max(50, Math.round(meters / C.METERS_PER_FOOT / 50) * 50);
            return ft + ' ft';
        }
        if (meters < C.METERS_PER_MILE) {
            return (meters / C.METERS_PER_MILE).toFixed(1) + ' mi';
        }
        const mi = meters / C.METERS_PER_MILE;
        if (mi < 10) return mi.toFixed(1) + ' mi';
        return Math.round(mi) + ' mi';
    }

    // Bucket order matters: highest-distance first. update() walks the list and
    // fires the highest-priority unfired bucket whose threshold has been crossed.
    function makeManeuverBuckets() {
        const C = window.TripWeather.Nav.Constants;
        return [
            {
                name: 'FAR',
                threshold: C.BUCKET_FAR_M,
                phrase: function(m) { return 'In 1 mile. ' + m.instruction + '.'; }
            },
            {
                name: 'MID',
                threshold: C.BUCKET_MID_M,
                phrase: function(m) { return 'In a quarter mile. ' + m.instruction + '.'; }
            },
            {
                name: 'NEAR',
                threshold: C.BUCKET_NEAR_M,
                phrase: function(m) { return 'In 500 feet. ' + m.instruction + '.'; }
            },
            {
                name: 'NOW',
                threshold: C.BUCKET_NOW_M,
                phrase: function(m) { return m.instruction + '.'; }
            }
        ];
    }

    // Buckets for waypoint-approach prompts (Phase 3d). Phrasing names the stop
    // ("Arriving at Mom's House in 1 mile"). NEAR is the last spoken bucket here
    // — actual arrival ("You have arrived at...") is fired separately by
    // NavigationManager when the user enters ARRIVAL_RADIUS_M of the waypoint,
    // because arrival also flips the manager into paused state.
    function makeWaypointBuckets() {
        const C = window.TripWeather.Nav.Constants;
        return [
            {
                name: 'FAR',
                threshold: C.BUCKET_FAR_M,
                phrase: function(w) { return 'In 1 mile, arriving at ' + (w.name || 'your stop') + '.'; }
            },
            {
                name: 'MID',
                threshold: C.BUCKET_MID_M,
                phrase: function(w) { return 'In a quarter mile, arriving at ' + (w.name || 'your stop') + '.'; }
            },
            {
                name: 'NEAR',
                threshold: C.BUCKET_NEAR_M,
                phrase: function(w) { return 'Arriving at ' + (w.name || 'your stop') + '.'; }
            }
        ];
    }

    // Buckets for the final-destination approach (Phase 3 polish). The side
    // phrase is set on the entry — "on the left" / "on the right" / "" when
    // unknown — and woven into the prompt only when known. The actual arrival
    // utterance ("You have arrived.") is fired separately by NavigationManager,
    // not from this scheduler.
    function makeFinalDestinationBuckets() {
        const C = window.TripWeather.Nav.Constants;
        const phrase = function(d, distLabel) {
            return d.sidePhrase
                ? 'In ' + distLabel + ', your destination will be on the ' + d.sidePhrase + '.'
                : 'In ' + distLabel + ', you will arrive at your destination.';
        };
        return [
            {
                name: 'FAR',
                threshold: C.BUCKET_FAR_M,
                phrase: function(d) { return phrase(d, '1 mile'); }
            },
            {
                name: 'MID',
                threshold: C.BUCKET_MID_M,
                phrase: function(d) { return phrase(d, 'a quarter mile'); }
            },
            {
                name: 'NEAR',
                threshold: C.BUCKET_NEAR_M,
                phrase: function(d) { return phrase(d, '500 feet'); }
            }
        ];
    }

    function Scheduler(items, options) {
        options = options || {};
        this.maneuvers = items || [];
        this.buckets = options.buckets || makeManeuverBuckets();
        // BUCKET_BUNCHING_M suppresses FAR when the previous item ends close to
        // the next; not relevant for waypoint-approach prompts.
        this.suppressBunching = !!options.suppressBunching;
        this.bucketsFired = this.maneuvers.map(function() { return {}; });
        this.currentIdx = 0;
    }

    Scheduler.prototype.reset = function(maneuvers) {
        this.maneuvers = maneuvers || [];
        this.bucketsFired = this.maneuvers.map(function() { return {}; });
        this.currentIdx = 0;
    };

    /**
     * Advance state given a new along-polyline distance and report what (if anything)
     * to speak. Returns:
     *   { phrase, nextManeuver, distanceToNext }
     * where phrase may be null (nothing to say this tick) and nextManeuver may be
     * null if the route is complete.
     */
    Scheduler.prototype.update = function(distanceAlongPolyline) {
        const C = window.TripWeather.Nav.Constants;

        // Advance past maneuvers we've already crossed (small fudge so we don't
        // re-fire the NOW bucket while sitting exactly on the maneuver point).
        while (this.currentIdx < this.maneuvers.length
            && this.maneuvers[this.currentIdx].distanceFromStart <= distanceAlongPolyline - 5) {
            this.currentIdx++;
        }

        if (this.currentIdx >= this.maneuvers.length) {
            return { phrase: null, nextManeuver: null, distanceToNext: 0 };
        }

        const next = this.maneuvers[this.currentIdx];
        const distToNext = Math.max(0, next.distanceFromStart - distanceAlongPolyline);

        let phrase = null;
        for (let i = 0; i < this.buckets.length; i++) {
            const bucket = this.buckets[i];
            if (distToNext > bucket.threshold) continue;
            if (this.bucketsFired[this.currentIdx][bucket.name]) continue;

            if (bucket.name === 'FAR' && !this.suppressBunching && this.currentIdx > 0) {
                const prev = this.maneuvers[this.currentIdx - 1];
                const gap = next.distanceFromStart - (prev.distanceFromStart + (prev.distance || 0));
                if (gap < C.BUCKET_BUNCHING_M) {
                    this.bucketsFired[this.currentIdx][bucket.name] = true;
                    continue;
                }
            }

            this.bucketsFired[this.currentIdx][bucket.name] = true;
            phrase = bucket.phrase(next);
            break;
        }

        return {
            phrase: phrase,
            nextManeuver: next,
            distanceToNext: distToNext
        };
    };

    window.TripWeather.Nav.ManeuverScheduler = {
        create: function(maneuvers) { return new Scheduler(maneuvers); },
        // Companion factory for waypoint-stop approach prompts. Same scheduling
        // logic but with stop-naming phrases and no FAR bunching suppression.
        createForWaypointStops: function(stops) {
            return new Scheduler(stops, {
                buckets: makeWaypointBuckets(),
                suppressBunching: true
            });
        },
        // One-element scheduler for the final-destination approach. The single
        // entry is `{ distanceFromStart, sidePhrase }`; sidePhrase is "left",
        // "right", or "" when undeterminable.
        createForFinalDestination: function(stop) {
            return new Scheduler([stop], {
                buckets: makeFinalDestinationBuckets(),
                suppressBunching: true
            });
        },
        formatDistance: formatDistance
    };

})();
