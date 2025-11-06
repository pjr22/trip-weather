package com.pjr22.tripweather.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class RouteData {
    
    @JsonProperty("geometry")
    private List<List<Double>> geometry;
    
    @JsonProperty("distance")
    private Double distance;
    
    @JsonProperty("duration")
    private Double duration;
    
    @JsonProperty("segments")
    private List<RouteSegment> segments;
    
    @JsonProperty("waypoints")
    private List<WaypointCoordinates> waypoints;
    
    public RouteData() {}
    
    public RouteData(List<List<Double>> geometry, Double distance, Double duration) {
        this.geometry = geometry;
        this.distance = distance;
        this.duration = duration;
    }
    
    public List<List<Double>> getGeometry() {
        return geometry;
    }
    
    public void setGeometry(List<List<Double>> geometry) {
        this.geometry = geometry;
    }
    
    public Double getDistance() {
        return distance;
    }
    
    public void setDistance(Double distance) {
        this.distance = distance;
    }
    
    public Double getDuration() {
        return duration;
    }
    
    public void setDuration(Double duration) {
        this.duration = duration;
    }
    
    public List<RouteSegment> getSegments() {
        return segments;
    }
    
    public void setSegments(List<RouteSegment> segments) {
        this.segments = segments;
    }
    
    public List<WaypointCoordinates> getWaypoints() {
        return waypoints;
    }
    
    public void setWaypoints(List<WaypointCoordinates> waypoints) {
        this.waypoints = waypoints;
    }
    
    public static class RouteSegment {
        @JsonProperty("distance")
        private Double distance;
        
        @JsonProperty("duration")
        private Double duration;
        
        @JsonProperty("steps")
        private List<Object> steps;
        
        public RouteSegment() {}
        
        public Double getDistance() {
            return distance;
        }
        
        public void setDistance(Double distance) {
            this.distance = distance;
        }
        
        public Double getDuration() {
            return duration;
        }
        
        public void setDuration(Double duration) {
            this.duration = duration;
        }
        
        public List<Object> getSteps() {
            return steps;
        }
        
        public void setSteps(List<Object> steps) {
            this.steps = steps;
        }
    }
    
    public static class WaypointCoordinates {
        @JsonProperty("location")
        private List<Double> location;
        
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("arrivalTime")
        private String arrivalTime;
        
        @JsonProperty("duration")
        private Integer duration; // minutes spent at waypoint
        
        @JsonProperty("timezone")
        private String timezone; // timezone identifier
        
        @JsonProperty("departureTime")
        private String departureTime; // calculated departure time
        
        public WaypointCoordinates() {}
        
        public WaypointCoordinates(List<Double> location, String name) {
            this.location = location;
            this.name = name;
            this.duration = 0; // default duration
        }
        
        public List<Double> getLocation() {
            return location;
        }
        
        public void setLocation(List<Double> location) {
            this.location = location;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getArrivalTime() {
            return arrivalTime;
        }
        
        public void setArrivalTime(String arrivalTime) {
            this.arrivalTime = arrivalTime;
        }
        
        public Integer getDuration() {
            return duration;
        }
        
        public void setDuration(Integer duration) {
            this.duration = duration;
        }
        
        public String getTimezone() {
            return timezone;
        }
        
        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
        
        public String getDepartureTime() {
            return departureTime;
        }
        
        public void setDepartureTime(String departureTime) {
            this.departureTime = departureTime;
        }
    }
}
