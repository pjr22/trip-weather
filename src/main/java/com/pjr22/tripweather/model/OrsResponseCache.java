package com.pjr22.tripweather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Durable cache of OpenRouteService responses (directions, snap, elevation).
 * The PK is the hex sha256 of the canonical request — coordinate-rounded,
 * key-sorted JSON for directions; rounded coordinate(s) for snap/elevation —
 * so two equivalent requests share a cache entry.
 */
@Entity
@Table(name = "ors_response_cache")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrsResponseCache {

    @Id
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "endpoint", nullable = false, length = 32)
    private String endpoint;

    @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String responseJson;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}
