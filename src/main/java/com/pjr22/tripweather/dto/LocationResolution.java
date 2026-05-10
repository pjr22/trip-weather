package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.GeoPoint;
import com.pjr22.tripweather.model.SnappedPoint;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resolves an input lat/lon to a navigation-ready point with elevation.
 *
 * <p>{@code original} is always the caller's input. {@code snapped} is always
 * present, with a uniform shape across both code paths:
 *
 * <ul>
 *   <li>Snap succeeds → {@code snapped} is the road point (lat/lon from ORS),
 *       {@code routable} is true, {@code elevation} is the road's elevation
 *       (extracted from a tiny self-loop directions request with
 *       {@code elevation: true}, served from the local graph when in coverage
 *       and the public engine otherwise).</li>
 *   <li>Snap fails → {@code snapped.lat/lon} mirror {@code original},
 *       {@code routable} is false, {@code elevation} is terrain elevation
 *       at the input point from the public {@code /elevation/point} fallback.
 *       Callers that gate on routability (e.g. map-click waypoint creation)
 *       reject the input here.</li>
 * </ul>
 *
 * <p>The original point is currently informational — the waypoint marker is
 * placed at {@code snapped}. A future feature will display both points and
 * draw a walking-path connector between them when they differ.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationResolution {

    private GeoPoint original;
    private SnappedPoint snapped;
}
