package com.example.orchestrator.implementation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassNameEquivalenceTest {

    @Test
    void recognizesTheExactReportedBugCase() {
        assertThat(ClassNameEquivalence.areEquivalent("UrlShortenerController", "ShortenController")).isTrue();
        assertThat(ClassNameEquivalence.areEquivalent("UrlShortenerService", "ShortenerService")).isTrue();
        assertThat(ClassNameEquivalence.areEquivalent("UrlShortenerRepository", "ShortUrlRepository")).isTrue();
    }

    @Test
    void identicalNamesAreEquivalent() {
        assertThat(ClassNameEquivalence.areEquivalent("ShortenController", "ShortenController")).isTrue();
    }

    @Test
    void unrelatedClassesAreNotEquivalent() {
        assertThat(ClassNameEquivalence.areEquivalent("PaymentController", "ShortenController")).isFalse();
        assertThat(ClassNameEquivalence.areEquivalent("UserRepository", "ClickEventRepository")).isFalse();
    }

    @Test
    void guessesKindFromConventionalSuffixes() {
        assertThat(ClassNameEquivalence.guessKindFromName("UrlShortenerService")).isEqualTo(ClassKind.SERVICE);
        assertThat(ClassNameEquivalence.guessKindFromName("ShortUrlRepository")).isEqualTo(ClassKind.REPOSITORY);
        assertThat(ClassNameEquivalence.guessKindFromName("ShortenController")).isEqualTo(ClassKind.CONTROLLER);
        assertThat(ClassNameEquivalence.guessKindFromName("NotFoundException")).isEqualTo(ClassKind.EXCEPTION);
        assertThat(ClassNameEquivalence.guessKindFromName("ShortUrl")).isEqualTo(ClassKind.OTHER);
    }
}
