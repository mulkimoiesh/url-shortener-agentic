package com.example.shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ShortenRequest(

        @NotBlank(message = "longUrl must not be blank")
        @Pattern(regexp = "^https?://.+", message = "longUrl must start with http:// or https://")
        String longUrl,

        Integer expiresInSeconds
) {
}
