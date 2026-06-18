package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.CreateProviderConfigRequest;
import com.pjr22.tripweather.dto.ProviderConfigSummary;
import com.pjr22.tripweather.dto.UpdateProviderConfigRequest;
import com.pjr22.tripweather.model.AiProvider;
import com.pjr22.tripweather.model.AiProviderConfig;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.AiProviderConfigRepository;
import com.pjr22.tripweather.security.AiKeyCipher;
import com.pjr22.tripweather.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiProviderConfigService}. AI_ASSIST_PLAN.md, Phase 1.
 * Covers ownership rejection (404), duplicate-nickname (409), provider-specific
 * validation, API-key encryption + keep-on-blank-update, and Ollama
 * availability gating. Pure Mockito, no Spring context — mirrors
 * {@link FavoriteWaypointServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class AiProviderConfigServiceTest {

    @Mock private AiProviderConfigRepository repository;
    @Mock private CurrentUserService currentUserService;
    @Mock private AiKeyCipher keyCipher;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(UUID.randomUUID());
        alice.setEmail("alice@example.com");
        alice.setName("alice");
    }

    /** Build a service with the given operator Ollama URL (blank = disabled). */
    private AiProviderConfigService service(String ollamaUrl) {
        return new AiProviderConfigService(repository, currentUserService, keyCipher, ollamaUrl);
    }

    /** Default: Ollama disabled. */
    private AiProviderConfigService service() {
        return service("");
    }

    private void asAlice() {
        when(currentUserService.currentUser()).thenReturn(Optional.of(alice));
    }

    private void asAnonymous() {
        when(currentUserService.currentUser()).thenReturn(Optional.empty());
    }

    /** encrypt() returns a recognizable token so assertions can check it ran. */
    private void stubEncrypt() {
        lenient().when(keyCipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
    }

    private void stubSaveEcho() {
        lenient().when(repository.save(any(AiProviderConfig.class))).thenAnswer(inv -> {
            AiProviderConfig c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            if (c.getCreated() == null) c.setCreated(ZonedDateTime.now());
            return c;
        });
    }

    private static CreateProviderConfigRequest createReq(AiProvider provider, String nickname,
                                                         String model, String apiKey, String baseUrl) {
        return new CreateProviderConfigRequest(provider, nickname, model, apiKey, baseUrl);
    }

    private AiProviderConfig entity(AiProvider provider, String nickname, String model,
                                    String apiKeyEncrypted, String baseUrl) {
        AiProviderConfig c = new AiProviderConfig();
        c.setId(UUID.randomUUID());
        c.setUser(alice);
        c.setProvider(provider);
        c.setNickname(nickname);
        c.setModel(model);
        c.setApiKeyEncrypted(apiKeyEncrypted);
        c.setBaseUrl(baseUrl);
        c.setCreated(ZonedDateTime.now());
        return c;
    }

    // ------------------------------------------------------------------------
    // list / get
    // ------------------------------------------------------------------------

    @Test
    void list_returnsSummaries_withoutKeys() {
        asAlice();
        when(repository.findAllByUser(alice.getId())).thenReturn(List.of(
                entity(AiProvider.OPENAI, "My OpenAI", "gpt-4o-mini", "enc:xxx", null),
                entity(AiProvider.CUSTOM, "Local", "llama", null, "https://x.example.com/v1")));

        List<ProviderConfigSummary> out = service().listForCurrentUser();

        assertThat(out).hasSize(2);
        assertThat(out).extracting(ProviderConfigSummary::nickname).containsExactly("My OpenAI", "Local");
        assertThat(out.get(0).apiKeySet()).isTrue();
        assertThat(out.get(1).apiKeySet()).isFalse();
    }

    @Test
    void list_anonymous_throwsNotFound() {
        asAnonymous();
        assertThatThrownBy(() -> service().listForCurrentUser())
                .isInstanceOf(AiProviderConfigService.ProviderConfigNotFoundException.class);
    }

    @Test
    void get_ownershipMiss_throwsNotFound() {
        asAlice();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(id))
                .isInstanceOf(AiProviderConfigService.ProviderConfigNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------------

    @Test
    void create_openai_encryptsKey_andTrimsNickname() {
        asAlice();
        stubEncrypt();
        stubSaveEcho();
        when(repository.existsByUserIdAndNicknameIgnoreCase(alice.getId(), "My OpenAI")).thenReturn(false);

        ProviderConfigSummary out = service().create(
                createReq(AiProvider.OPENAI, "  My OpenAI  ", "gpt-4o-mini", "sk-secret", null));

        assertThat(out.nickname()).isEqualTo("My OpenAI");
        assertThat(out.provider()).isEqualTo(AiProvider.OPENAI);
        assertThat(out.model()).isEqualTo("gpt-4o-mini");
        assertThat(out.baseUrl()).isNull();
        assertThat(out.apiKeySet()).isTrue();

        ArgumentCaptor<AiProviderConfig> captor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(repository).save(captor.capture());
        AiProviderConfig saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(alice);
        assertThat(saved.getApiKeyEncrypted()).isEqualTo("enc:sk-secret");
        verify(keyCipher).encrypt("sk-secret");
    }

    @Test
    void create_custom_requiresBaseUrl() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.CUSTOM, "Local", "llama", null, null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("baseUrl");
        verify(repository, never()).save(any());
    }

    @Test
    void create_custom_withBaseUrl_noKey_ok() {
        asAlice();
        stubSaveEcho();
        when(repository.existsByUserIdAndNicknameIgnoreCase(alice.getId(), "Local")).thenReturn(false);

        ProviderConfigSummary out = service().create(
                createReq(AiProvider.CUSTOM, "Local", "llama", null, "https://host.example.com/v1"));

        assertThat(out.baseUrl()).isEqualTo("https://host.example.com/v1");
        assertThat(out.apiKeySet()).isFalse();
        verify(keyCipher, never()).encrypt(anyString());
    }

    @Test
    void create_custom_invalidBaseUrl_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.CUSTOM, "Local", "llama", null, "ftp://nope")))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class);
    }

    @Test
    void create_openai_missingKey_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.OPENAI, "X", "gpt-4o-mini", null, null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void create_anthropic_missingKey_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.ANTHROPIC, "X", "claude-opus-4-8", "   ", null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class);
    }

    @Test
    void create_missingProvider_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(null, "X", "m", "k", null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void create_blankNickname_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.OPENAI, "  ", "m", "k", null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("nickname");
    }

    @Test
    void create_blankModel_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.OPENAI, "X", " ", "k", null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("model");
    }

    @Test
    void create_duplicateNickname_throwsConflict() {
        asAlice();
        stubEncrypt();
        when(repository.existsByUserIdAndNicknameIgnoreCase(alice.getId(), "Dup")).thenReturn(true);

        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.OPENAI, "Dup", "m", "k", null)))
                .isInstanceOf(AiProviderConfigService.DuplicateNicknameException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void create_ollama_whenDisabled_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service("").create(
                createReq(AiProvider.OLLAMA, "Local", "llama", null, null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("Ollama");
    }

    @Test
    void create_ollama_whenEnabled_ok() {
        asAlice();
        stubSaveEcho();
        when(repository.existsByUserIdAndNicknameIgnoreCase(alice.getId(), "Local")).thenReturn(false);

        ProviderConfigSummary out = service("http://localhost:11434").create(
                createReq(AiProvider.OLLAMA, "Local", "llama3.1", null, null));

        assertThat(out.provider()).isEqualTo(AiProvider.OLLAMA);
        assertThat(out.apiKeySet()).isFalse();
        assertThat(out.baseUrl()).isNull();
    }

    @Test
    void create_anonymous_throwsNotFound() {
        asAnonymous();
        assertThatThrownBy(() -> service().create(
                createReq(AiProvider.OPENAI, "X", "m", "k", null)))
                .isInstanceOf(AiProviderConfigService.ProviderConfigNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------------

    private UpdateProviderConfigRequest updateReq(AiProvider provider, String nickname,
                                                  String model, String apiKey, String baseUrl) {
        return new UpdateProviderConfigRequest(provider, nickname, model, apiKey, baseUrl);
    }

    @Test
    void update_blankApiKey_keepsStoredKey() {
        asAlice();
        stubSaveEcho();
        AiProviderConfig existing = entity(AiProvider.OPENAI, "My OpenAI", "gpt-4o-mini", "enc:old", null);
        when(repository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));

        ProviderConfigSummary out = service().update(existing.getId(),
                updateReq(AiProvider.OPENAI, "My OpenAI", "gpt-4o", "  ", null));

        assertThat(out.model()).isEqualTo("gpt-4o");
        assertThat(out.apiKeySet()).isTrue();
        assertThat(existing.getApiKeyEncrypted()).isEqualTo("enc:old");
        verify(keyCipher, never()).encrypt(anyString());
    }

    @Test
    void update_nonBlankApiKey_reEncrypts() {
        asAlice();
        stubEncrypt();
        stubSaveEcho();
        AiProviderConfig existing = entity(AiProvider.OPENAI, "My OpenAI", "gpt-4o-mini", "enc:old", null);
        when(repository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));

        service().update(existing.getId(),
                updateReq(AiProvider.OPENAI, "My OpenAI", "gpt-4o-mini", "sk-new", null));

        assertThat(existing.getApiKeyEncrypted()).isEqualTo("enc:sk-new");
        verify(keyCipher).encrypt("sk-new");
    }

    @Test
    void update_changeProviderToKeyRequired_withoutKey_throwsInvalid() {
        asAlice();
        // Existing CUSTOM config with no stored key; switching to OPENAI and
        // not supplying a key must fail (effective key would be null).
        AiProviderConfig existing = entity(AiProvider.CUSTOM, "X", "m", null, "https://x.example.com/v1");
        when(repository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().update(existing.getId(),
                updateReq(AiProvider.OPENAI, "X", "m", null, null)))
                .isInstanceOf(AiProviderConfigService.InvalidProviderConfigException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void update_duplicateNickname_throwsConflict() {
        asAlice();
        AiProviderConfig existing = entity(AiProvider.OPENAI, "Old", "m", "enc:old", null);
        when(repository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));
        when(repository.existsByUserIdAndNicknameIgnoreCase(alice.getId(), "Taken")).thenReturn(true);

        assertThatThrownBy(() -> service().update(existing.getId(),
                updateReq(AiProvider.OPENAI, "Taken", "m", null, null)))
                .isInstanceOf(AiProviderConfigService.DuplicateNicknameException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void update_ownershipMiss_throwsNotFound() {
        asAlice();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(id,
                updateReq(AiProvider.OPENAI, "X", "m", "k", null)))
                .isInstanceOf(AiProviderConfigService.ProviderConfigNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // softDelete
    // ------------------------------------------------------------------------

    @Test
    void softDelete_setsDeletedAtAndSaves() {
        asAlice();
        AiProviderConfig existing = entity(AiProvider.OPENAI, "X", "m", "enc:old", null);
        when(repository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ZonedDateTime before = ZonedDateTime.now();
        service().softDelete(existing.getId());
        ZonedDateTime after = ZonedDateTime.now();

        assertThat(existing.getDeletedAt()).isNotNull();
        assertThat(existing.getDeletedAt()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        verify(repository).save(existing);
    }

    @Test
    void softDelete_ownershipMiss_throwsNotFound() {
        asAlice();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().softDelete(id))
                .isInstanceOf(AiProviderConfigService.ProviderConfigNotFoundException.class);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------------
    // availability
    // ------------------------------------------------------------------------

    @Test
    void availableProviders_ollamaDisabled_excludesOllama() {
        assertThat(service("").availableProviders())
                .containsExactly(AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.CUSTOM);
    }

    @Test
    void availableProviders_ollamaEnabled_includesOllama() {
        assertThat(service("http://localhost:11434").availableProviders())
                .containsExactly(AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.CUSTOM, AiProvider.OLLAMA);
    }

    @Test
    void isOllamaEnabled_invalidUrl_isFalse() {
        assertThat(service("not-a-url").isOllamaEnabled()).isFalse();
        assertThat(service("").isOllamaEnabled()).isFalse();
        assertThat(service("http://localhost:11434").isOllamaEnabled()).isTrue();
    }
}
