package com.pjr22.tripweather.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the dispatch decision tree without standing up real HTTP clients.
 * Both {@link OrsClient} implementations are stub instances that return
 * preset payloads or throw, so each test isolates one branch.
 */
@ExtendWith(MockitoExtension.class)
class RoutingDispatcherTest {

    private static final String ENDPOINT = "directions";
    private static final List<double[]> COORDS =
            List.of(new double[]{-105.0, 39.7}, new double[]{-104.9, 39.8});
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private RoutingCoverageRepository coverageRepository;
    @Mock private ObjectProvider<LocalOrsClient> localProvider;

    private MeterRegistry registry;
    private RoutingMetrics metrics;
    private StubClient publicClient;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RoutingMetrics(registry);
        publicClient = new StubClient("public");
    }

    @Test
    void disabled_routes_to_public_when_no_local_client() throws Exception {
        when(localProvider.getIfAvailable()).thenReturn(null);
        publicClient.nextResponse = MAPPER.createObjectNode().put("ok", true);

        RoutingDispatcher dispatcher = newDispatcher();
        JsonNode result = dispatcher.dispatch(ENDPOINT, COORDS,
                client -> client.post("/p", "body"));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(publicClient.calls).isEqualTo(1);
        // Coverage repo should never be touched on the disabled-fast-path —
        // skipping that DB roundtrip is the whole point of the early exit.
        verifyNoInteractions(coverageRepository);
        assertCount("trip.routing.local.fallback", "reason", "disabled", 1);
        assertCount("trip.routing.public.calls", "endpoint", ENDPOINT, 1);
    }

    @Test
    void out_of_coverage_routes_to_public() throws Exception {
        StubLocal localClient = new StubLocal();
        when(localProvider.getIfAvailable()).thenReturn(localClient);
        when(coverageRepository.coversAll(anyString())).thenReturn(false);
        publicClient.nextResponse = MAPPER.createObjectNode().put("ok", true);

        RoutingDispatcher dispatcher = newDispatcher();
        dispatcher.dispatch(ENDPOINT, COORDS, client -> client.post("/p", "body"));

        assertThat(publicClient.calls).isEqualTo(1);
        assertThat(localClient.calls).isZero();
        assertCount("trip.routing.local.fallback", "reason", "out_of_coverage", 1);
    }

    @Test
    void in_coverage_calls_local_and_records_success() throws Exception {
        StubLocal localClient = new StubLocal();
        localClient.nextResponse = MAPPER.createObjectNode().put("via", "local");
        when(localProvider.getIfAvailable()).thenReturn(localClient);
        when(coverageRepository.coversAll(anyString())).thenReturn(true);

        RoutingDispatcher dispatcher = newDispatcher();
        JsonNode result = dispatcher.dispatch(ENDPOINT, COORDS,
                client -> client.post("/p", "body"));

        assertThat(result.path("via").asText()).isEqualTo("local");
        assertThat(localClient.calls).isEqualTo(1);
        assertThat(publicClient.calls).isZero();
        assertCount("trip.routing.local.success", "endpoint", ENDPOINT, 1);
    }

    @Test
    void timeout_falls_back_to_public_with_timeout_tag() throws Exception {
        StubLocal localClient = new StubLocal();
        localClient.nextException = new ResourceAccessException("connect timed out");
        when(localProvider.getIfAvailable()).thenReturn(localClient);
        when(coverageRepository.coversAll(anyString())).thenReturn(true);
        publicClient.nextResponse = MAPPER.createObjectNode().put("ok", true);

        RoutingDispatcher dispatcher = newDispatcher();
        dispatcher.dispatch(ENDPOINT, COORDS, client -> client.post("/p", "body"));

        assertThat(localClient.calls).isEqualTo(1);
        assertThat(publicClient.calls).isEqualTo(1);
        assertCount("trip.routing.local.fallback", "reason", "timeout", 1);
    }

    @Test
    void other_error_falls_back_to_public_with_upstream_error_tag() throws Exception {
        StubLocal localClient = new StubLocal();
        localClient.nextException = new RestClientException("500 from local");
        when(localProvider.getIfAvailable()).thenReturn(localClient);
        when(coverageRepository.coversAll(anyString())).thenReturn(true);
        publicClient.nextResponse = MAPPER.createObjectNode().put("ok", true);

        RoutingDispatcher dispatcher = newDispatcher();
        dispatcher.dispatch(ENDPOINT, COORDS, client -> client.post("/p", "body"));

        assertCount("trip.routing.local.fallback", "reason", "upstream_error", 1);
    }

    @Test
    void coverage_db_error_treats_as_uncovered() throws Exception {
        StubLocal localClient = new StubLocal();
        when(localProvider.getIfAvailable()).thenReturn(localClient);
        when(coverageRepository.coversAll(anyString()))
                .thenThrow(new RuntimeException("postgres down"));
        publicClient.nextResponse = MAPPER.createObjectNode();

        RoutingDispatcher dispatcher = newDispatcher();
        dispatcher.dispatch(ENDPOINT, COORDS, client -> client.post("/p", "body"));

        assertThat(localClient.calls).isZero();
        assertThat(publicClient.calls).isEqualTo(1);
        assertCount("trip.routing.local.fallback", "reason", "out_of_coverage", 1);
    }

    @Test
    void public_failure_propagates_when_no_local_client() {
        when(localProvider.getIfAvailable()).thenReturn(null);
        publicClient.nextException = new RestClientException("public 503");

        RoutingDispatcher dispatcher = newDispatcher();
        assertThatThrownBy(() -> dispatcher.dispatch(ENDPOINT, COORDS,
                client -> client.post("/p", "body")))
                .isInstanceOf(RestClientException.class);
    }

    private RoutingDispatcher newDispatcher() {
        // PublicOrsClient is a final class with no helpful constructor for
        // tests, but the dispatcher only depends on the OrsClient interface
        // surface — we wire the stub directly with reflection-free injection
        // by going through the only seam the dispatcher offers.
        // See bottom of the file for the test-only subclass that bypasses
        // the @Component constructor.
        return new RoutingDispatcher(
                new TestablePublicOrsClient(publicClient),
                localProvider, coverageRepository, metrics);
    }

    private void assertCount(String name, String tagKey, String tagValue, double expected) {
        // RoutingMetrics eagerly registers all (endpoint × reason) combinations
        // at startup, so a single-tag search like `tag("reason", "timeout")`
        // matches one counter per endpoint. Sum across the matches — each test
        // exercises one combination so non-matching counters stay at 0.
        double actual = registry.find(name).tag(tagKey, tagValue).counters()
                .stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        assertThat(actual).isEqualTo(expected);
    }

    /** Bare-minimum {@link OrsClient} stub — records calls, returns preset payload or throws. */
    private static class StubClient implements OrsClient {
        final String name;
        int calls = 0;
        JsonNode nextResponse;
        RuntimeException nextException;

        StubClient(String name) { this.name = name; }

        @Override public JsonNode post(String path, Object body) {
            calls++;
            if (nextException != null) throw nextException;
            return nextResponse;
        }
        @Override public JsonNode get(String path) {
            calls++;
            if (nextException != null) throw nextException;
            return nextResponse;
        }
        @Override public String name() { return name; }
    }

    /** LocalOrsClient is final-ish (Spring's @Component) but its only state
     *  is the RestClient — extending it via a stub is too brittle. We use a
     *  lookalike here and never inject this into production wiring. */
    private static class StubLocal extends LocalOrsClient {
        int calls = 0;
        JsonNode nextResponse;
        RuntimeException nextException;

        StubLocal() {
            super(org.springframework.web.client.RestClient.builder().build());
        }

        @Override public JsonNode post(String path, Object body) {
            calls++;
            if (nextException != null) throw nextException;
            return nextResponse;
        }
        @Override public JsonNode get(String path) {
            calls++;
            if (nextException != null) throw nextException;
            return nextResponse;
        }
    }

    /** Minimal PublicOrsClient that ignores apiKey/RestClient and forwards to a stub. */
    private static class TestablePublicOrsClient extends PublicOrsClient {
        private final StubClient delegate;

        TestablePublicOrsClient(StubClient delegate) {
            super(org.springframework.web.client.RestClient.builder().build(), "test-key");
            this.delegate = delegate;
        }

        @Override public JsonNode post(String path, Object body) { return delegate.post(path, body); }
        @Override public JsonNode get(String path) { return delegate.get(path); }
    }
}
