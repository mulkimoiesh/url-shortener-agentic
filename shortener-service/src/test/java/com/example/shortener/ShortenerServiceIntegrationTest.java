package com.example.shortener;

import com.example.shortener.dto.ShortenRequest;
import com.example.shortener.dto.ShortenResponse;
import com.example.shortener.dto.StatsResponse;
import com.example.shortener.exception.ShortUrlNotFoundException;
import com.example.shortener.service.ShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ShortenerServiceIntegrationTest {

    @Autowired
    private ShortenerService shortenerService;

    @Test
    void createsShortUrlWithSevenCharacterCode() {
        ShortenResponse response = shortenerService.createShortUrl(new ShortenRequest("https://example.com/page", null));

        assertNotNull(response.code());
        assertEquals(7, response.code().length());
        assertEquals("https://example.com/page", response.longUrl());
    }

    @Test
    void resolveReturnsOriginalUrlAndRecordsClick() {
        ShortenResponse created = shortenerService.createShortUrl(new ShortenRequest("https://example.com/resolve-me", null));

        String longUrl = shortenerService.resolveAndRecordClick(created.code());
        assertEquals("https://example.com/resolve-me", longUrl);

        StatsResponse stats = shortenerService.getStats(created.code());
        assertEquals(1, stats.totalClicks());
    }

    @Test
    void multipleClicksAreCountedCorrectly() {
        ShortenResponse created = shortenerService.createShortUrl(new ShortenRequest("https://example.com/multi", null));

        shortenerService.resolveAndRecordClick(created.code());
        shortenerService.resolveAndRecordClick(created.code());
        shortenerService.resolveAndRecordClick(created.code());

        StatsResponse stats = shortenerService.getStats(created.code());
        assertEquals(3, stats.totalClicks());
    }

    @Test
    void resolvingUnknownCodeThrowsNotFound() {
        assertThrows(ShortUrlNotFoundException.class,
                () -> shortenerService.resolveAndRecordClick("doesNotExist"));
    }

    @Test
    void expiredShortUrlIsTreatedAsNotFound() {
        ShortenResponse created = shortenerService.createShortUrl(new ShortenRequest("https://example.com/expiring", -1));

        assertThrows(ShortUrlNotFoundException.class,
                () -> shortenerService.resolveAndRecordClick(created.code()));
    }
}
