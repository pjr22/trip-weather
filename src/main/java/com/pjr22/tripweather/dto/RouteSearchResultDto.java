package com.pjr22.tripweather.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteSearchResultDto {
    
    private UUID id;
    private String name;
    private ZonedDateTime created;
    private UUID userId;

}
