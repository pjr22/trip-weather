package com.pjr22.tripweather.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Represents the capabilities data for a WMS layer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CapabilitiesData {
    private String layerName;
    private String layerTitle;
    private String layerDescription;
    private List<String> srsValues;
    private BoundingBox boundingBox;
    private List<Double> resolutions;
    private int validTimesCount;
    private long validTimesInterval; // in minutes
    private List<String> validTimes; // Raw valid times from the XML
}