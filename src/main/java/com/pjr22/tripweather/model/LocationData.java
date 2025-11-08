package com.pjr22.tripweather.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Setter
@ToString
@Accessors(chain = true)
public class LocationData {
    
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("features")
    private List<Feature> features;
    
    @JsonProperty("query")
    private Query query;
    
    // Inner classes for nested JSON structure
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Feature {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("properties")
        private Properties properties;
        
        @JsonProperty("geometry")
        private Geometry geometry;
        
        @JsonProperty("bbox")
        private List<Double> bbox;
    }
    
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Properties {
        @JsonProperty("datasource")
        private Datasource datasource;
        
        @JsonProperty("country")
        private String country;
        
        @JsonProperty("country_code")
        private String countryCode;
        
        @JsonProperty("state")
        private String state;
        
        @JsonProperty("city")
        private String city;
        
        @JsonProperty("postcode")
        private String postcode;
        
        @JsonProperty("district")
        private String district;
        
        @JsonProperty("street")
        private String street;
        
        @JsonProperty("housenumber")
        private String housenumber;
        
        @JsonProperty("iso3166_2")
        private String iso3166_2;
        
        @JsonProperty("lon")
        private Double lon;
        
        @JsonProperty("lat")
        private Double lat;
        
        @JsonProperty("state_code")
        private String stateCode;
        
        @JsonProperty("distance")
        private Double distance;
        
        @JsonProperty("result_type")
        private String resultType;
        
        @JsonProperty("formatted")
        private String formatted;
        
        @JsonProperty("address_line1")
        private String addressLine1;
        
        @JsonProperty("address_line2")
        private String addressLine2;
        
        @JsonProperty("category")
        private String category;
        
        @JsonProperty("timezone")
        private Timezone timezone;
        
        @JsonProperty("plus_code")
        private String plusCode;
        
        @JsonProperty("plus_code_short")
        private String plusCodeShort;
        
        @JsonProperty("rank")
        private Rank rank;
        
        @JsonProperty("place_id")
        private String placeId;
    }
    
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Datasource {
        @JsonProperty("sourcename")
        private String sourcename;
        
        @JsonProperty("attribution")
        private String attribution;
        
        @JsonProperty("license")
        private String license;
        
        @JsonProperty("url")
        private String url;
    }
    
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Timezone {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("offset_STD")
        private String offsetStd;
        
        @JsonProperty("offset_STD_seconds")
        private Integer offsetStdSeconds;
        
        @JsonProperty("offset_DST")
        private String offsetDst;
        
        @JsonProperty("offset_DST_seconds")
        private Integer offsetDstSeconds;
        
        @JsonProperty("abbreviation_STD")
        private String abbreviationStd;
        
        @JsonProperty("abbreviation_DST")
        private String abbreviationDst;
    }
    
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Rank {
        @JsonProperty("importance")
        private Double importance;
        
        @JsonProperty("popularity")
        private Double popularity;
    }
    
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Geometry {
        @JsonProperty("type")
        private String type;
        
        @JsonProperty("coordinates")
        private List<Double> coordinates;
    }
    
    @Getter
    @Setter
    @ToString
    @Accessors(chain = true)
    public static class Query {
        @JsonProperty("lat")
        private Double lat;
        
        @JsonProperty("lon")
        private Double lon;
        
        @JsonProperty("plus_code")
        private String plusCode;
    }
}
