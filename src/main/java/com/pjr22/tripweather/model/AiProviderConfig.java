package com.pjr22.tripweather.model;

import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * A saved AI-provider configuration belonging to an authenticated user. One
 * user may have many configs (e.g. "my OpenAI", "work Anthropic", "local
 * Ollama"). AI_ASSIST_PLAN.md, Phase 1.
 *
 * <p>Mirrors {@link FavoriteWaypoint}: per-user FK with {@code ON DELETE
 * CASCADE}, soft-delete enforced at the entity level via {@link SQLRestriction}
 * (every JPA load / find / JPQL query silently filters out rows where
 * {@code deleted_at IS NOT NULL}), and a partial unique index on
 * {@code (user_id, LOWER(nickname)) WHERE deleted_at IS NULL} so a nickname
 * freed by soft-delete becomes reusable.
 *
 * <p>The API key is stored <b>encrypted at rest</b> in {@code apiKeyEncrypted}
 * (AES-GCM via {@code AiKeyCipher}); the plaintext never lives on the entity
 * and is never serialized back to the client. {@code baseUrl} is user-supplied
 * only for {@link AiProvider#CUSTOM} — the other providers use server defaults
 * (or, for Ollama, the operator's {@code trip.ai.ollama-url}).
 */
@Entity
@Table(name = "ai_provider_configs")
@SQLRestriction("deleted_at IS NULL")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiProviderConfig {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, referencedColumnName = "id",
                foreignKey = @ForeignKey(
                    name = "ai_provider_configs_user_id_fkey",
                    foreignKeyDefinition = "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private AiProvider provider;

    @Column(name = "nickname", nullable = false, length = 255)
    private String nickname;

    @Column(name = "model", nullable = false, length = 255)
    private String model;

    /**
     * AES-GCM ciphertext of the user's API key (base64 {@code iv ‖ ciphertext ‖
     * tag}), produced by {@code AiKeyCipher}. Nullable: {@link AiProvider#CUSTOM}
     * and {@link AiProvider#OLLAMA} may have no key. Never exposed in a DTO.
     */
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    /**
     * OpenAI-compatible API root (including {@code /v1}) for
     * {@link AiProvider#CUSTOM}. Null for the other providers, which resolve
     * their base URL from server config at call time.
     */
    @Column(name = "base_url", length = 1023)
    private String baseUrl;

    /**
     * Optional user-supplied price, in USD per 1,000,000 input (prompt) tokens,
     * used to estimate the dollar cost of an assist run. Null = not configured
     * (no cost shown). There's no provider API for unit prices, so the user
     * copies these from the provider's pricing page.
     */
    @Column(name = "input_cost_per_mtok")
    private Double inputCostPerMtok;

    /** Optional price in USD per 1,000,000 output (completion) tokens. See {@link #inputCostPerMtok}. */
    @Column(name = "output_cost_per_mtok")
    private Double outputCostPerMtok;

    @Column(name = "created", nullable = false)
    private ZonedDateTime created;

    /**
     * Soft-delete marker. {@code null} means active; non-null is when the config
     * was deleted. The entity-level {@link SQLRestriction} hides any row with a
     * non-null value from every JPA-level query.
     */
    @Column(name = "deleted_at")
    private ZonedDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (created == null) {
            created = ZonedDateTime.now();
        }
    }
}
