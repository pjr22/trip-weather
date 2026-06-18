package com.pjr22.tripweather.service.ai;

import com.pjr22.tripweather.dto.ModelDiscoveryRequest;
import com.pjr22.tripweather.model.AiProvider;
import com.pjr22.tripweather.model.AiProviderConfig;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.AiProviderConfigRepository;
import com.pjr22.tripweather.security.AiKeyCipher;
import com.pjr22.tripweather.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link AiModelDiscoveryService}. AI_ASSIST_PLAN.md, Phase 1b.
 * Uses {@link MockRestServiceServer} for the provider HTTP and a fake DNS
 * resolver in the SSRF guard (the Custom host "custom.test" resolves to a
 * public IP; everything else is unresolvable). No real network.
 */
class AiModelDiscoveryServiceTest {

    private static final String OPENAI_BASE = "https://api.openai.test/v1";
    private static final String ANTHROPIC_BASE = "https://api.anthropic.test";
    private static final String OLLAMA_URL = "http://ollama.test:11434";

    private RestClient client;
    private MockRestServiceServer server;
    private OutboundUrlGuard guard;
    private CurrentUserService currentUserService;
    private AiProviderConfigRepository repository;
    private AiKeyCipher keyCipher;
    private AiModelDiscoveryService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();

        guard = new OutboundUrlGuard((String host) -> {
            if ("custom.test".equals(host)) {
                return new InetAddress[]{ InetAddress.getByName("93.184.216.34") };
            }
            throw new UnknownHostException(host);
        });

        currentUserService = mock(CurrentUserService.class);
        repository = mock(AiProviderConfigRepository.class);
        keyCipher = mock(AiKeyCipher.class);

        service = newService(OLLAMA_URL);
    }

    private AiModelDiscoveryService newService(String ollamaUrl) {
        return new AiModelDiscoveryService(client, guard, currentUserService, repository, keyCipher,
                OPENAI_BASE, ANTHROPIC_BASE, "2023-06-01", ollamaUrl);
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode().value()).isEqualTo(expected.value());
    }

    // ------------------------------------------------------------------------
    // discover() — per provider
    // ------------------------------------------------------------------------

    @Test
    void discover_openai_sendsBearer_parsesData() {
        server.expect(requestTo(OPENAI_BASE + "/models"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk-x"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}",
                        MediaType.APPLICATION_JSON));

        List<String> models = service.discover(new ModelDiscoveryRequest(AiProvider.OPENAI, "sk-x", null));

        assertThat(models).containsExactly("gpt-4o", "gpt-4o-mini");
        server.verify();
    }

    @Test
    void discover_anthropic_sendsApiKeyAndVersionHeaders_parsesData() {
        server.expect(requestTo(ANTHROPIC_BASE + "/v1/models"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("x-api-key", "sk-ant"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"claude-opus-4-8\"}]}",
                        MediaType.APPLICATION_JSON));

        List<String> models = service.discover(new ModelDiscoveryRequest(AiProvider.ANTHROPIC, "sk-ant", null));

        assertThat(models).containsExactly("claude-opus-4-8");
        server.verify();
    }

    @Test
    void discover_ollama_usesTagsEndpoint_parsesNames() {
        server.expect(requestTo(OLLAMA_URL + "/api/tags"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"models\":[{\"name\":\"llama3.1\"},{\"name\":\"mistral\"}]}",
                        MediaType.APPLICATION_JSON));

        List<String> models = service.discover(new ModelDiscoveryRequest(AiProvider.OLLAMA, null, null));

        assertThat(models).containsExactly("llama3.1", "mistral");
        server.verify();
    }

    @Test
    void discover_custom_passesGuard_andParses() {
        server.expect(requestTo("https://custom.test/v1/models"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk-c"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"local-model\"}]}",
                        MediaType.APPLICATION_JSON));

        List<String> models = service.discover(
                new ModelDiscoveryRequest(AiProvider.CUSTOM, "sk-c", "https://custom.test/v1"));

        assertThat(models).containsExactly("local-model");
        server.verify();
    }

    @Test
    void discover_dedupesAndTrimsModelIds() {
        server.expect(requestTo(OPENAI_BASE + "/models"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\" gpt-4o \"},{\"id\":\"gpt-4o-mini\"},{\"id\":\"\"}]}",
                        MediaType.APPLICATION_JSON));

        List<String> models = service.discover(new ModelDiscoveryRequest(AiProvider.OPENAI, "sk-x", null));

        assertThat(models).containsExactly("gpt-4o", "gpt-4o-mini");
    }

    // ------------------------------------------------------------------------
    // discover() — error mapping
    // ------------------------------------------------------------------------

    @Test
    void discover_providerUnauthorized_mapsTo400() {
        server.expect(requestTo(OPENAI_BASE + "/models"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> service.discover(new ModelDiscoveryRequest(AiProvider.OPENAI, "bad-key", null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void discover_providerServerError_mapsTo502() {
        server.expect(requestTo(OPENAI_BASE + "/models"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.discover(new ModelDiscoveryRequest(AiProvider.OPENAI, "sk-x", null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_GATEWAY));
    }

    // ------------------------------------------------------------------------
    // discover() — validation
    // ------------------------------------------------------------------------

    @Test
    void discover_ollamaDisabled_throws400() {
        AiModelDiscoveryService noOllama = newService("");
        assertThatThrownBy(() -> noOllama.discover(new ModelDiscoveryRequest(AiProvider.OLLAMA, null, null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void discover_customMissingBaseUrl_throws400() {
        assertThatThrownBy(() -> service.discover(new ModelDiscoveryRequest(AiProvider.CUSTOM, "sk-c", null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void discover_openaiMissingKey_throws400() {
        assertThatThrownBy(() -> service.discover(new ModelDiscoveryRequest(AiProvider.OPENAI, null, null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void discover_customPrivateHost_rejectedByGuard() {
        assertThatThrownBy(() -> service.discover(
                new ModelDiscoveryRequest(AiProvider.CUSTOM, "sk-c", "http://localhost/v1")))
                .isInstanceOf(OutboundUrlGuard.OutboundUrlNotAllowedException.class);
    }

    @Test
    void discover_nullRequest_throws400() {
        assertThatThrownBy(() -> service.discover(null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    // ------------------------------------------------------------------------
    // discoverForConfig()
    // ------------------------------------------------------------------------

    @Test
    void discoverForConfig_decryptsStoredKey_andFetches() {
        User alice = new User();
        alice.setId(UUID.randomUUID());
        UUID configId = UUID.randomUUID();

        AiProviderConfig config = new AiProviderConfig();
        config.setId(configId);
        config.setUser(alice);
        config.setProvider(AiProvider.OPENAI);
        config.setModel("gpt-4o-mini");
        config.setApiKeyEncrypted("enc-blob");

        when(currentUserService.currentUser()).thenReturn(Optional.of(alice));
        when(repository.findByIdAndUserId(configId, alice.getId())).thenReturn(Optional.of(config));
        when(keyCipher.decrypt("enc-blob")).thenReturn("sk-real");

        server.expect(requestTo(OPENAI_BASE + "/models"))
                .andExpect(header("Authorization", "Bearer sk-real"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"gpt-4o\"}]}", MediaType.APPLICATION_JSON));

        List<String> models = service.discoverForConfig(configId);

        assertThat(models).containsExactly("gpt-4o");
        server.verify();
    }

    @Test
    void discoverForConfig_ownershipMiss_throws404() {
        User alice = new User();
        alice.setId(UUID.randomUUID());
        UUID configId = UUID.randomUUID();
        when(currentUserService.currentUser()).thenReturn(Optional.of(alice));
        when(repository.findByIdAndUserId(configId, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.discoverForConfig(configId))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }
}
