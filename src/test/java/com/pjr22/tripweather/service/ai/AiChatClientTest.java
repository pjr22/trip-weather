package com.pjr22.tripweather.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link OpenAiCompatibleChatClient} and
 * {@link AnthropicChatClient}. AI_ASSIST_PLAN.md, Phase 2. Verifies request
 * shape (path, headers, body), content extraction, and error mapping via
 * {@link MockRestServiceServer}. No real network.
 */
class AiChatClientTest {

    private MockRestServiceServer server;
    private OpenAiCompatibleChatClient openAi;
    private AnthropicChatClient anthropic;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        openAi = new OpenAiCompatibleChatClient(client);
        anthropic = new AnthropicChatClient(client, "2023-06-01");
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode().value()).isEqualTo(expected.value());
    }

    // ------------------------------------------------------------------- OpenAI

    @Test
    void openai_jsonMode_postsMessages_andExtractsContent() {
        server.expect(requestTo("https://api.openai.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-x"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("sys"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("usr"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"HELLO\"}}],"
                                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22,\"total_tokens\":33}}",
                        MediaType.APPLICATION_JSON));

        AiChatResult out = openAi.complete(new AiChatCall(
                "https://api.openai.test/v1", "gpt-4o-mini", "sk-x", "sys", "usr", true));

        assertThat(out.content()).isEqualTo("HELLO");
        assertThat(out.promptTokens()).isEqualTo(11);
        assertThat(out.completionTokens()).isEqualTo(22);
        assertThat(out.totalTokens()).isEqualTo(33);
        server.verify();
    }

    @Test
    void openai_noJsonMode_omitsResponseFormat() {
        server.expect(requestTo("https://api.openai.test/v1/chat/completions"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"X\"}}]}",
                        MediaType.APPLICATION_JSON));

        AiChatResult out = openAi.complete(new AiChatCall(
                "https://api.openai.test/v1", "m", "sk-x", "sys", "usr", false));

        assertThat(out.content()).isEqualTo("X");
        // No usage block in this response — token figures stay null.
        assertThat(out.totalTokens()).isNull();
        server.verify();
    }

    @Test
    void openai_unauthorized_mapsTo400() {
        server.expect(requestTo("https://api.openai.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> openAi.complete(new AiChatCall(
                "https://api.openai.test/v1", "m", "bad", "sys", "usr", true)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void openai_emptyContent_mapsTo502() {
        server.expect(requestTo("https://api.openai.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> openAi.complete(new AiChatCall(
                "https://api.openai.test/v1", "m", "sk", "sys", "usr", true)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_GATEWAY));
    }

    // ---------------------------------------------------------------- Anthropic

    @Test
    void anthropic_postsMessages_withHeaders_andExtractsText() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "sk-ant"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
                .andExpect(jsonPath("$.system").value("sys"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("usr"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"HI\"}],"
                                + "\"usage\":{\"input_tokens\":7,\"output_tokens\":5}}",
                        MediaType.APPLICATION_JSON));

        AiChatResult out = anthropic.complete(new AiChatCall(
                "https://api.anthropic.test", "claude-opus-4-8", "sk-ant", "sys", "usr", false));

        assertThat(out.content()).isEqualTo("HI");
        assertThat(out.promptTokens()).isEqualTo(7);
        assertThat(out.completionTokens()).isEqualTo(5);
        // total = input + output.
        assertThat(out.totalTokens()).isEqualTo(12);
        server.verify();
    }

    @Test
    void anthropic_serverError_mapsTo502() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> anthropic.complete(new AiChatCall(
                "https://api.anthropic.test", "m", "sk", "sys", "usr", false)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_GATEWAY));
    }
}
