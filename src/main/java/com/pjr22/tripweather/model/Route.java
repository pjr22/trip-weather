package com.pjr22.tripweather.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "routes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Route {
    
    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;
    
    @Column(name = "name", nullable = false, length = 255)
    private String name;
    
    @Column(name = "created", nullable = false)
    private LocalDateTime created;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, referencedColumnName = "id")
    private User user;
    
    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<Waypoint> waypoints;
    
    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (created == null) {
            created = LocalDateTime.now();
        }
    }
}
