package com.pjr22.tripweather.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The point we'd actually navigate to. {@code routable} distinguishes the two
 * paths that produce this:
 * <ul>
 *   <li>{@code routable=true}: ORS snap returned a road point. {@code lat}/{@code lon}
 *       are the road point; {@code elevation} is the road's elevation as
 *       baked into the ORS graph.</li>
 *   <li>{@code routable=false}: snap found no road within the search radius.
 *       {@code lat}/{@code lon} mirror the original input and {@code elevation}
 *       is terrain elevation from the public {@code /elevation/point} fallback,
 *       or null if that also failed. Callers gating on routability (the
 *       map-click waypoint flow) reject the input.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnappedPoint {
    private double lat;
    private double lon;
    private Double elevation;
    private boolean routable;
}
