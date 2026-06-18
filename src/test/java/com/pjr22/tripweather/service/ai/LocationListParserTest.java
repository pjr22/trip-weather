package com.pjr22.tripweather.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LocationListParser}. AI_ASSIST_PLAN.md, Phase 2. Covers
 * clean JSON, markdown fences, surrounding prose, blank-name filtering, unknown
 * fields, and the unparseable cases that trigger the repair re-ask upstream.
 */
class LocationListParserTest {

    private final LocationListParser parser = new LocationListParser(new ObjectMapper());

    @Test
    void parsesCleanJson() {
        List<AiLocation> out = parser.parse(
                "{\"locations\":[{\"name\":\"Moab\",\"city\":\"Moab\",\"state\":\"UT\"},"
              + "{\"name\":\"Aspen\",\"city\":\"Aspen\",\"state\":\"CO\"}]}");

        assertThat(out).extracting(AiLocation::name).containsExactly("Moab", "Aspen");
        assertThat(out.get(0).state()).isEqualTo("UT");
    }

    @Test
    void parsesJsonInMarkdownFence() {
        String text = "```json\n{\"locations\":[{\"name\":\"Denver\",\"city\":\"Denver\",\"state\":\"CO\"}]}\n```";
        assertThat(parser.parse(text)).extracting(AiLocation::name).containsExactly("Denver");
    }

    @Test
    void parsesJsonWithSurroundingProse() {
        String text = "Sure! Here is your trip:\n{\"locations\":[{\"name\":\"Taos\",\"city\":\"Taos\",\"state\":\"NM\"}]}\nEnjoy!";
        assertThat(parser.parse(text)).extracting(AiLocation::name).containsExactly("Taos");
    }

    @Test
    void skipsEntriesWithBlankName() {
        List<AiLocation> out = parser.parse(
                "{\"locations\":[{\"name\":\"A\",\"city\":\"\",\"state\":\"\"},"
              + "{\"name\":\"  \",\"city\":\"x\",\"state\":\"y\"}]}");
        assertThat(out).extracting(AiLocation::name).containsExactly("A");
    }

    @Test
    void ignoresUnknownFields() {
        List<AiLocation> out = parser.parse(
                "{\"locations\":[{\"name\":\"A\",\"city\":\"C\",\"state\":\"S\",\"notes\":\"stay 2 nights\"}],\"extra\":1}");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).name()).isEqualTo("A");
    }

    @Test
    void throwsWhenNoJsonObject() {
        assertThatThrownBy(() -> parser.parse("I cannot help with that."))
                .isInstanceOf(LocationListParser.LocationParseException.class);
    }

    @Test
    void throwsOnBlankInput() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(LocationListParser.LocationParseException.class);
    }

    @Test
    void throwsOnMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{\"locations\": [ broken"))
                .isInstanceOf(LocationListParser.LocationParseException.class);
    }
}
