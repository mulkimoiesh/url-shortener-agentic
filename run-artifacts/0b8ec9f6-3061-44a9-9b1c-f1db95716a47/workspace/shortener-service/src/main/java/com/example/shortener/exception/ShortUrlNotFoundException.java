package com.example.shortener.exception;

public class ShortUrlNotFoundException extends RuntimeException {
    public ShortUrlNotFoundException(String code) {
        super("No active short URL found for code: " + code);
    }
}
