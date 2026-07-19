package com.example.shortener.dto;

import java.time.Instant;

public record ShortenResponse(
        String code,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {
}
