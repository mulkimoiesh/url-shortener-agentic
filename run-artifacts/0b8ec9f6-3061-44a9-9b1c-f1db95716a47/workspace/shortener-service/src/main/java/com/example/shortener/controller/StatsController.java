package com.example.shortener.controller;

import com.example.shortener.dto.StatsResponse;
import com.example.shortener.service.ShortenerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    private final ShortenerService shortenerService;

    public StatsController(ShortenerService shortenerService) {
        this.shortenerService = shortenerService;
    }

    @GetMapping("/api/v1/{code}/stats")
    public StatsResponse stats(@PathVariable String code) {
        return shortenerService.getStats(code);
    }
}
