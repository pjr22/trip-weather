package com.pjr22.tripweather.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "waypoints")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Waypoint {
    
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;
    
    @Column(name = "sequence", nullable = false)
    private Integer sequence;
    
    @Column(name = "date")
    private String date;
    
    @Column(name = "time")
    private String time;
    
    @Column(name = "timezone")
    private String timezone;
    
    @Column(name = "duration_min")
    private Integer durationMin;
    
    @Column(name = "location_name", length = 1023)
    private String locationName;
    
    @Column(name = "latitude", nullable = false)
    private Double latitude;
    
    @Column(name = "longitude", nullable = false)
    private Double longitude;
    
    @Column(name = "elevation")
    private Double elevation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false, referencedColumnName = "id")
    private Route route;
    
    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
