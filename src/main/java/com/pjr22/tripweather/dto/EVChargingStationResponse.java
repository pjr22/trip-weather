package com.pjr22.tripweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO for the NREL EV charging stations API response
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EVChargingStationResponse {
    
    private String type;
    private EVChargingStationMetadata metadata;
    private List<EVChargingStationFeature> features;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EVChargingStationMetadata {
        private String station_locator_url;
        private Integer total_results;
        private Map<String, Object> station_counts;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EVChargingStationFeature {
        private String type;
        private EVChargingStationGeometry geometry;
        private EVChargingStationProperties properties;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EVChargingStationGeometry {
        private String type;
        private List<Double> coordinates;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EVChargingStationProperties {
        private String access_code;
        private String access_days_time;
        private String date_last_confirmed;
        private String fuel_type_code;
        private String groups_with_access_code;
        private Integer id;
        private String open_date;
        private String station_name;
        private String station_phone;
        private String city;
        private String country;
        private String state;
        private String street_address;
        private String zip;
        private List<String> ev_connector_types;
        private Integer ev_dc_fast_num;
        private Integer ev_level1_evse_num;
        private Integer ev_level2_evse_num;
        private String ev_network;
        private String ev_network_web;
        private Boolean ev_workplace_charging;
        private List<EVChargingUnit> ev_charging_units;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EVChargingUnit {
        private String network;
        private Map<String, EVConnector> connectors;
        private Integer port_count;
        private String charging_level;
        private List<String> funding_sources;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EVConnector {
        private Double power_kw;
        private Integer port_count;
    }
}