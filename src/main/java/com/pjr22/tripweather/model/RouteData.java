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
        
        public WaypointCoordinates() {}
        
        public WaypointCoordinates(List<Double> location, String name) {
            this.location = location;
            this.name = name;
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
    }
}
