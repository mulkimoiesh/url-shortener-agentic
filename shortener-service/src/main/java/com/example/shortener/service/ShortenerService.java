package com.example.shortener.service;

import com.example.shortener.dto.ShortenRequest;
import com.example.shortener.dto.ShortenResponse;
import com.example.shortener.dto.StatsResponse;
import com.example.shortener.exception.ShortUrlNotFoundException;
import com.example.shortener.model.ClickEvent;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class ShortenerService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 7;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final ShortUrlRepository shortUrlRepository;
    private final ClickEventRepository clickEventRepository;
    private final SecureRandom random = new SecureRandom();

    public ShortenerService(ShortUrlRepository shortUrlRepository, ClickEventRepository clickEventRepository) {
        this.shortUrlRepository = shortUrlRepository;
        this.clickEventRepository = clickEventRepository;
    }

    public ShortenResponse createShortUrl(ShortenRequest request) {
        String code = generateUniqueCode();
        Instant now = Instant.now();
        Instant expiresAt = request.expiresInSeconds() != null
                ? now.plus(request.expiresInSeconds(), ChronoUnit.SECONDS)
                : null;

        ShortUrl saved = shortUrlRepository.save(new ShortUrl(code, request.longUrl(), now, expiresAt));

        return new ShortenResponse(saved.getCode(), "/" + saved.getCode(), saved.getLongUrl(),
                saved.getCreatedAt(), saved.getExpiresAt());
    }

    public String resolveAndRecordClick(String code) {
        ShortUrl shortUrl = shortUrlRepository.findById(code)
                .orElseThrow(() -> new ShortUrlNotFoundException(code));

        if (shortUrl.isExpired()) {
            throw new ShortUrlNotFoundException(code);
        }

        clickEventRepository.save(new ClickEvent(code, Instant.now()));
        return shortUrl.getLongUrl();
    }

    public StatsResponse getStats(String code) {
        ShortUrl shortUrl = shortUrlRepository.findById(code)
                .orElseThrow(() -> new ShortUrlNotFoundException(code));

        long totalClicks = clickEventRepository.countByCode(code);
        return new StatsResponse(shortUrl.getCode(), shortUrl.getLongUrl(), totalClicks);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!shortUrlRepository.existsById(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique code after "
                + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
