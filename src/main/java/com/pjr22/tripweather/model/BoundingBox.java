package com.pjr22.tripweather.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Represents a bounding box with minimum and maximum coordinates.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BoundingBox {
    private double minx;
    private double miny;
    private double maxx;
    private double maxy;
    private String srs;
}