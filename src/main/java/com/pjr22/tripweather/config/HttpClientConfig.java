package com.pjr22.tripweather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

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
        // The NREL/NLR /alt-fuel-stations endpoint has no offset parameter,
        // so the only way to get the full ~70K-row ELEC dataset is one
        // limit=all call returning ~100 MB. With the JDK HttpClient's
        // default HTTP/2 transport, that response trips an RST_STREAM
        // mid-stream (server-side stream reset). HTTP/1.1 has no stream
        // concept and accepts the long-running response cleanly.
        // EvStationLoader pairs this with a Jackson streaming parser so the
        // 100 MB is never resident in memory at once.
        HttpClient jdkHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(jdkHttpClient))
                .build();
    }
}
