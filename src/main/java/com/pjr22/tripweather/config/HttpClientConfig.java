package com.pjr22.tripweather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Centralized RestClient beans for the external HTTP services we talk to.
 * Each service has its own bean wired with the right baseUrl + headers, so
 * service classes receive a ready-to-use client and can be unit-tested with
 * Spring's MockRestServiceServer.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient nwsRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.weather.gov")
                .defaultHeader("User-Agent", "TripWeather/1.0 (tripweather.app)")
                .build();
    }

    @Bean
    public RestClient geoapifyRestClient(
            @Value("${geoapify.base.url:https://api.geoapify.com/v1}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient orsRestClient(
            @Value("${openrouteservice.base.url:https://api.openrouteservice.org}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient nrelRestClient(
            @Value("${nrel.base.url:https://developer.nrel.gov}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
