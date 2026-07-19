package com.example.shortener.dto;

public record StatsResponse(
        String code,
        String longUrl,
        long totalClicks
) {
}
