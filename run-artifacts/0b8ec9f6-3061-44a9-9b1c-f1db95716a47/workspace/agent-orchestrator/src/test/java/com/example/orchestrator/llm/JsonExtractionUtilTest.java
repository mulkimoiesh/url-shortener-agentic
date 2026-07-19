package com.example.orchestrator.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExtractionUtilTest {

    @Test
    void passesThroughCleanJson() {
        String input = "{\"a\":1}";
        assertThat(JsonExtractionUtil.extractJson(input)).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsMarkdownFencesWithLanguageTag() {
        String input = "```json\n{\"a\":1}\n```";
        assertThat(JsonExtractionUtil.extractJson(input)).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsMarkdownFencesWithoutLanguageTag() {
        String input = "```\n{\"a\":1}\n```";
        assertThat(JsonExtractionUtil.extractJson(input)).isEqualTo("{\"a\":1}");
    }

    @Test
    void stripsLeadingAndTrailingCommentaryOutsideBraces() {
        String input = "Sure, here is the JSON:\n{\"a\":1}\nHope that helps!";
        assertThat(JsonExtractionUtil.extractJson(input)).isEqualTo("{\"a\":1}");
    }

    @Test
    void handlesNullGracefully() {
        assertThat(JsonExtractionUtil.extractJson(null)).isEqualTo("{}");
    }
}
