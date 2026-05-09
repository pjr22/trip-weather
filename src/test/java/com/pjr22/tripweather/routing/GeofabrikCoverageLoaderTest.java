package com.pjr22.tripweather.routing;

import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class GeofabrikCoverageLoaderTest {

    private static final String FIXTURE_POLY = """
            colorado
            1
                -109.05  41.00
                -102.04  41.00
                -102.04  37.00
                -109.05  37.00
                -109.05  41.00
            END
            END
            """;

    @Mock private RoutingCoverageRepository repository;

    private MockRestServiceServer mockServer;
    private RestClient geofabrikRestClient;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://download.geofabrik.de/north-america/us");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        geofabrikRestClient = builder.build();
        fixedClock = Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void seedMissingRegions_skips_existing_rows() {
        when(repository.existsById("colorado")).thenReturn(true);

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, "colorado");
        loader.seedMissingRegions();

        // No HTTP request, no upsert.
        mockServer.verify();
        verify(repository, never()).upsert(anyString(), anyString(), any());
    }

    @Test
    void seedMissingRegions_fetches_and_upserts_when_row_absent() {
        when(repository.existsById("colorado")).thenReturn(false);
        mockServer.expect(requestTo(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"))
                .andRespond(withSuccess(FIXTURE_POLY, MediaType.TEXT_PLAIN));

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, "colorado");
        loader.seedMissingRegions();

        ArgumentCaptor<String> wktCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(eq("colorado"), wktCaptor.capture(),
                eq(java.time.LocalDateTime.now(fixedClock.withZone(java.time.ZoneId.systemDefault()))));
        // WKT should be a MULTIPOLYGON (PolyParser always wraps; the
        // dispatcher's spatial query relies on this shape).
        assertThat(wktCaptor.getValue()).startsWith("MULTIPOLYGON");
    }

    @Test
    void seedMissingRegions_swallows_per_region_failures() {
        when(repository.existsById("colorado")).thenReturn(false);
        mockServer.expect(requestTo(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"))
                .andRespond(withServerError());

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, "colorado");
        // Should not throw — failure is logged and the dispatcher safely
        // treats the region as uncovered until the next refresh.
        loader.seedMissingRegions();

        verify(repository, never()).upsert(anyString(), anyString(), any());
    }

    @Test
    void refresh_throws_for_unknown_region() {
        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, "colorado");

        assertThatThrownBy(() -> loader.refresh("wyoming"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in trip.routing.local-regions");
    }

    @Test
    void refresh_re_fetches_even_if_row_exists() {
        // refresh() bypasses the existsById check on purpose — it's the
        // post-pbf-swap entry point; the polygon may have changed.
        mockServer.expect(requestTo(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"))
                .andRespond(withSuccess(FIXTURE_POLY, MediaType.TEXT_PLAIN));

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, "colorado");
        loader.refresh("colorado");

        verify(repository).upsert(eq("colorado"), startsWithMultipolygon(),
                any(java.time.LocalDateTime.class));
    }

    // ---- Mockito helpers --------------------------------------------------

    private static String anyString()    { return org.mockito.ArgumentMatchers.anyString(); }
    private static <T> T any()           { return org.mockito.ArgumentMatchers.any(); }
    private static <T> T any(Class<T> c) { return org.mockito.ArgumentMatchers.any(c); }
    private static <T> T eq(T v)         { return org.mockito.ArgumentMatchers.eq(v); }

    /** Argument matcher that asserts the captured string starts with MULTIPOLYGON. */
    private static String startsWithMultipolygon() {
        return org.mockito.ArgumentMatchers.argThat(s ->
                s != null && s.startsWith("MULTIPOLYGON"));
    }
}
