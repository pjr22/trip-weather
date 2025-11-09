package com.pjr22.tripweather.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteDto {
    
    private UUID id;
    private String name;
    private ZonedDateTime created;
    private UUID userId;
    private List<WaypointDto> waypoints;
}
