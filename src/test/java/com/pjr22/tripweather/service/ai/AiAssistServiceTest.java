package com.pjr22.tripweather.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pjr22.tripweather.dto.AiAssistRequest;
import com.pjr22.tripweather.dto.AiAssistResponse;
import com.pjr22.tripweather.model.AiProvider;
import com.pjr22.tripweather.model.AiProviderConfig;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.AiProviderConfigRepository;
import com.pjr22.tripweather.security.AiKeyCipher;
import com.pjr22.tripweather.security.CurrentUserService;
import com.pjr22.tripweather.service.LocationService;
import com.pjr22.tripweather.service.RouteService;
import com.pjr22.tripweather.service.ai.LocationListParser.LocationParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiAssistService}. AI_ASSIST_PLAN.md, Phase 2. Mocks the
 * chat, geocode, and routing seams to cover the happy path, partial geocode
 * failure, the &lt;2-resolved no-route case, the max-waypoints cap, the
 * parse-then-repair flow, and the error paths (config not found, parse fails
 * twice, blank input).
 */
@ExtendWith(MockitoExtension.class)
class AiAssistServiceTest {

    private static final String SYS = "SYSTEM-PROMPT";
    private static final String USER = "USER-PROMPT";
    private static final String REPAIR = "REPAIR-PROMPT";
    private static final String RAW = "raw-model-text";

    @Mock private AiProviderConfigRepository repository;
    @Mock private CurrentUserService currentUserService;
    @Mock private AiKeyCipher keyCipher;
    @Mock private AiChatService chatService;
    @Mock private AssistPromptBuilder promptBuilder;
    @Mock private LocationListParser parser;
    @Mock private LocationService locationService;
    @Mock private RouteService routeService;

    private final ObjectMapper mapper = new ObjectMapper();

    private User alice;
    private AiProviderConfig config;
    private UUID configId;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(UUID.randomUUID());
        configId = UUID.randomUUID();
        config = new AiProviderConfig();
        config.setId(configId);
        config.setUser(alice);
        config.setProvider(AiProvider.OPENAI);
        config.setModel("gpt-4o-mini");
        config.setApiKeyEncrypted("enc");
    }

    private AiAssistService service(int maxWaypoints, boolean debug) {
        // Default retries=1 (prod default) with a 0s delay so tests never sleep.
        return service(maxWaypoints, debug, 1, 0);
    }

    private AiAssistService service(int maxWaypoints, boolean debug, int retries, int delaySeconds) {
        return new AiAssistService(repository, currentUserService, keyCipher, chatService,
                promptBuilder, parser, locationService, routeService, maxWaypoints, debug,
                retries, delaySeconds);
    }

    private AiAssistService service() {
        return service(25, false);
    }

    private void asAlice() {
        when(currentUserService.currentUser()).thenReturn(Optional.of(alice));
        when(repository.findByIdAndUserId(configId, alice.getId())).thenReturn(Optional.of(config));
        lenient().when(keyCipher.decrypt("enc")).thenReturn("sk-real");
    }

    private void stubPrompts() {
        when(promptBuilder.systemPrompt()).thenReturn(SYS);
        when(promptBuilder.userPrompt(anyString())).thenReturn(USER);
    }

    /** A minimal Geoapify forward-geocode response with one feature. */
    private com.fasterxml.jackson.databind.JsonNode geo(double lat, double lon,
                                                        String formatted, String city, String state) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode feature = root.putArray("features").addObject();
        ObjectNode props = feature.putObject("properties");
        props.put("lat", lat);
        props.put("lon", lon);
        props.put("formatted", formatted);
        props.put("city", city);
        props.put("state_code", state);
        return root;
    }

    private static RouteData routeWithGeometry() {
        RouteData rd = new RouteData();
        rd.setGeometry(List.of(List.of(-105.0, 40.0), List.of(-106.0, 39.0)));
        rd.setDistance(1000.0);
        rd.setDuration(600.0);
        return rd;
    }

    private AiAssistRequest req() {
        return new AiAssistRequest(configId, "drive from A to B");
    }

    /** Wrap model text as a chat result with no token usage reported. */
    private static AiChatResult chat(String content) {
        return new AiChatResult(content, null, null, null);
    }

    // ------------------------------------------------------------------------

    @Test
    void happyPath_resolvesWaypoints_andCalculatesRoute() {
        asAlice();
        stubPrompts();
        when(chatService.complete(eq(AiProvider.OPENAI), eq("gpt-4o-mini"), eq("sk-real"),
                eq(null), eq(SYS), eq(USER))).thenReturn(new AiChatResult(RAW, 11, 22, 33));
        when(parser.parse(RAW)).thenReturn(List.of(
                new AiLocation("Moab", "Moab", "UT"),
                new AiLocation("Aspen", "Aspen", "CO")));
        when(locationService.searchLocations("Moab, Moab, UT"))
                .thenReturn(geo(38.57, -109.55, "Moab, UT, USA", "Moab", "UT"));
        when(locationService.searchLocations("Aspen, Aspen, CO"))
                .thenReturn(geo(39.19, -106.82, "Aspen, CO, USA", "Aspen", "CO"));
        when(routeService.calculateRoute(any(), any(), any())).thenReturn(routeWithGeometry());

        AiAssistResponse resp = service().assist(req());

        assertThat(resp.waypoints()).hasSize(2);
        assertThat(resp.waypoints().get(0).locationName()).isEqualTo("Moab, UT, USA");
        assertThat(resp.waypoints().get(0).latitude()).isEqualTo(38.57);
        assertThat(resp.waypoints().get(0).sequence()).isZero();
        assertThat(resp.waypoints().get(1).city()).isEqualTo("Aspen");
        assertThat(resp.waypoints().get(1).sequence()).isEqualTo(1);
        assertThat(resp.route()).isNotNull();
        assertThat(resp.unresolved()).isEmpty();
        assertThat(resp.warnings()).isEmpty();
        assertThat(resp.details()).isNotNull();
        assertThat(resp.details().rawResponse()).isEqualTo(RAW);
        assertThat(resp.details().promptTokens()).isEqualTo(11);
        assertThat(resp.details().completionTokens()).isEqualTo(22);
        assertThat(resp.details().totalTokens()).isEqualTo(33);
    }

    @Test
    void details_alwaysReturned_withRawResponseModelAndTiming() {
        asAlice();
        stubPrompts();
        // debug=false — details are always populated, not gated behind debug.
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(parser.parse(RAW)).thenReturn(List.of(
                new AiLocation("Moab", "Moab", "UT"),
                new AiLocation("Aspen", "Aspen", "CO")));
        when(locationService.searchLocations(anyString()))
                .thenReturn(geo(38.57, -109.55, "x", "Moab", "UT"));
        when(routeService.calculateRoute(any(), any(), any())).thenReturn(routeWithGeometry());

        AiAssistResponse resp = service(25, false).assist(req());

        assertThat(resp.details()).isNotNull();
        assertThat(resp.details().model()).isEqualTo("gpt-4o-mini");
        assertThat(resp.details().rawResponse()).isEqualTo(RAW);
        assertThat(resp.details().elapsedMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void partialGeocodeFailure_warnsAndOmits() {
        asAlice();
        stubPrompts();
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(parser.parse(RAW)).thenReturn(List.of(
                new AiLocation("Moab", "Moab", "UT"),
                new AiLocation("Nowhere", "", "")));
        when(locationService.searchLocations("Moab, Moab, UT"))
                .thenReturn(geo(38.57, -109.55, "Moab, UT", "Moab", "UT"));
        when(locationService.searchLocations("Nowhere")).thenReturn(null);

        AiAssistResponse resp = service().assist(req());

        assertThat(resp.waypoints()).hasSize(1);
        assertThat(resp.waypoints().get(0).sequence()).isZero();
        // The "Nowhere" miss is now a structured unresolved entry carrying its
        // failed query and 0-based sequence (it was second in the AI's ordering).
        assertThat(resp.unresolved()).hasSize(1);
        assertThat(resp.unresolved().get(0).sequence()).isEqualTo(1);
        assertThat(resp.unresolved().get(0).query()).isEqualTo("Nowhere");
        // The couldn't-find line is no longer a warning; only the route-level
        // need-at-least-2 message remains.
        assertThat(resp.warnings()).noneMatch(w -> w.contains("Nowhere"));
        assertThat(resp.warnings()).anyMatch(w -> w.contains("at least 2"));
        assertThat(resp.route()).isNull();
    }

    @Test
    void geocode_retriesOnTransientFailure_thenSucceeds() {
        asAlice();
        stubPrompts();
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(parser.parse(RAW)).thenReturn(List.of(
                new AiLocation("Moab", "Moab", "UT"),
                new AiLocation("Aspen", "Aspen", "CO")));
        // First Moab lookup returns nothing; the retry succeeds.
        when(locationService.searchLocations("Moab, Moab, UT"))
                .thenReturn(null, geo(38.57, -109.55, "Moab, UT", "Moab", "UT"));
        when(locationService.searchLocations("Aspen, Aspen, CO"))
                .thenReturn(geo(39.19, -106.82, "Aspen, CO", "Aspen", "CO"));
        when(routeService.calculateRoute(any(), any(), any())).thenReturn(routeWithGeometry());

        AiAssistResponse resp = service().assist(req()); // retries=1, delay=0

        assertThat(resp.waypoints()).hasSize(2);
        assertThat(resp.warnings()).isEmpty();
        assertThat(resp.route()).isNotNull();
        verify(locationService, times(2)).searchLocations("Moab, Moab, UT");
        verify(locationService, times(1)).searchLocations("Aspen, Aspen, CO");
    }

    @Test
    void geocode_persistentFailure_retriesThenWarns() {
        asAlice();
        stubPrompts();
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(parser.parse(RAW)).thenReturn(List.of(
                new AiLocation("Moab", "Moab", "UT"),
                new AiLocation("Aspen", "Aspen", "CO")));
        when(locationService.searchLocations(anyString())).thenReturn(null); // always empty

        AiAssistResponse resp = service().assist(req()); // retries=1, delay=0

        assertThat(resp.waypoints()).isEmpty();
        assertThat(resp.route()).isNull();
        // Both misses become unresolved entries in sequence order; warnings holds
        // only the route-level need-at-least-2 message.
        assertThat(resp.unresolved()).extracting(u -> u.query())
                .containsExactly("Moab, Moab, UT", "Aspen, Aspen, CO");
        assertThat(resp.unresolved()).extracting(u -> u.sequence()).containsExactly(0, 1);
        assertThat(resp.warnings()).anyMatch(w -> w.contains("at least 2"));
        // retries=1 -> 2 attempts per location.
        verify(locationService, times(2)).searchLocations("Moab, Moab, UT");
        verify(locationService, times(2)).searchLocations("Aspen, Aspen, CO");
    }

    @Test
    void geocode_noRetry_whenRetriesZero() {
        asAlice();
        stubPrompts();
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(parser.parse(RAW)).thenReturn(List.of(new AiLocation("Moab", "Moab", "UT")));
        when(locationService.searchLocations("Moab, Moab, UT")).thenReturn(null);

        AiAssistResponse resp = service(25, false, 0, 0).assist(req());

        assertThat(resp.waypoints()).isEmpty();
        verify(locationService, times(1)).searchLocations("Moab, Moab, UT");
    }

    @Test
    void maxWaypointsCap_trimsAndWarns() {
        asAlice();
        stubPrompts();
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(parser.parse(RAW)).thenReturn(List.of(
                new AiLocation("A", "A", "CO"),
                new AiLocation("B", "B", "CO"),
                new AiLocation("C", "C", "CO")));
        when(locationService.searchLocations(anyString()))
                .thenReturn(geo(40.0, -105.0, "x", "x", "CO"));
        when(routeService.calculateRoute(any(), any(), any())).thenReturn(routeWithGeometry());

        AiAssistResponse resp = service(2, false).assist(req());

        assertThat(resp.waypoints()).hasSize(2);
        assertThat(resp.warnings()).anyMatch(w -> w.contains("using the first 2"));
    }

    @Test
    void parseFailsThenRepairSucceeds() {
        asAlice();
        stubPrompts();
        when(promptBuilder.repairSystemPrompt()).thenReturn(REPAIR);
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(chatService.complete(any(), any(), any(), any(), eq(REPAIR), eq(RAW))).thenReturn(chat("repaired"));
        when(parser.parse(RAW)).thenThrow(new LocationParseException("bad"));
        when(parser.parse("repaired")).thenReturn(List.of(
                new AiLocation("Moab", "Moab", "UT"),
                new AiLocation("Aspen", "Aspen", "CO")));
        when(locationService.searchLocations(anyString()))
                .thenReturn(geo(38.57, -109.55, "x", "Moab", "UT"));
        when(routeService.calculateRoute(any(), any(), any())).thenReturn(routeWithGeometry());

        AiAssistResponse resp = service().assist(req());

        assertThat(resp.waypoints()).hasSize(2);
    }

    @Test
    void parseFailsTwice_throws502() {
        asAlice();
        stubPrompts();
        when(promptBuilder.repairSystemPrompt()).thenReturn(REPAIR);
        when(chatService.complete(any(), any(), any(), any(), eq(SYS), any())).thenReturn(chat(RAW));
        when(chatService.complete(any(), any(), any(), any(), eq(REPAIR), eq(RAW))).thenReturn(chat("still-bad"));
        when(parser.parse(RAW)).thenThrow(new LocationParseException("bad"));
        when(parser.parse("still-bad")).thenThrow(new LocationParseException("bad again"));

        assertThatThrownBy(() -> service().assist(req()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void configNotOwned_throws404() {
        when(currentUserService.currentUser()).thenReturn(Optional.of(alice));
        when(repository.findByIdAndUserId(configId, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assist(req()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void blankPrompt_throws400() {
        assertThatThrownBy(() -> service().assist(new AiAssistRequest(configId, "   ")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void nullProviderConfigId_throws400() {
        assertThatThrownBy(() -> service().assist(new AiAssistRequest(null, "go")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
