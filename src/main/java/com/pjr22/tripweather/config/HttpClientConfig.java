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

    /**
     * Local OpenRouteService instance (Phase 5 of LOCAL_CACHING_HOSTING.md).
     * Separate bean from {@link #orsRestClient} so the dispatch wrapper in
     * {@code RouteService} can call either at runtime. No Authorization
     * header — local ORS is unauthenticated on its container-internal port.
     * The base URL defaults to the {@code trip-ors} container name on
     * {@code forgotten_net}; override with {@code TRIP_LOCAL_ORS_BASE_URL}
     * for dev (e.g. {@code http://localhost:8082/ors}). Note: the ORS v8+
     * image listens on port 8082 inside the container, not 8080.
     *
     * <p>Both the connect and read timeouts are set to
     * {@code trip.local.ors.timeout-ms}: the dispatch wrapper falls back to
     * public ORS on any timeout, so a slow local engine should fail fast
     * rather than make the user wait. JdkClientHttpRequestFactory propagates
     * the connect timeout to the underlying HttpClient and the read timeout
     * per-request via {@link java.net.http.HttpRequest#timeout}.
     */
    @Bean
    public RestClient localOrsRestClient(
            @Value("${trip.local.ors.base-url:http://trip-ors:8082/ors}") String baseUrl,
            @Value("${trip.local.ors.timeout-ms:3000}") long timeoutMs) {
        java.time.Duration timeout = java.time.Duration.ofMillis(timeoutMs);
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Geofabrik download endpoint, used by GeofabrikCoverageLoader to fetch
     * .poly files for the routing-coverage table. Default points at the
     * North America / US bucket; an override is useful for tests that serve
     * a fixture from MockRestServiceServer.
     */
    @Bean
    public RestClient geofabrikRestClient(
            @Value("${trip.routing.geofabrik-base-url:https://download.geofabrik.de/north-america/us}") String baseUrl) {
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
