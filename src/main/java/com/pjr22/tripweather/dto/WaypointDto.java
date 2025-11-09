package com.pjr22.tripweather.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WaypointDto {
    
    private UUID id;
    private Integer sequence;
    private ZonedDateTime dateTime;
    private Integer durationMin;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private UUID routeId;
}
