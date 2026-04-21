package com.pjr22.tripweather.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RouteCalculateRequest {

    @NotNull
    @Size(min = 2, message = "at least 2 waypoints are required")
    @Valid
    private List<WaypointInput> waypoints;

    @Setter
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaypointInput {

        @NotNull
        @DecimalMin(value = "-90.0", message = "latitude must be >= -90")
        @DecimalMax(value = "90.0", message = "latitude must be <= 90")
        private Double latitude;

        @NotNull
        @DecimalMin(value = "-180.0", message = "longitude must be >= -180")
        @DecimalMax(value = "180.0", message = "longitude must be <= 180")
        private Double longitude;

        private String name;
        private String date;
        private String time;
        private String timezoneName;

        @Min(value = 0, message = "duration must be >= 0")
        private Integer duration;
    }
}
