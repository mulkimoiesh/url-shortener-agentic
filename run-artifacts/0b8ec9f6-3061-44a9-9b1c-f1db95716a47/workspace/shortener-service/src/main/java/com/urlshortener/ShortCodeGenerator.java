package com.urlshortener;

import java.security.SecureRandom;
import java.util.Base64;

public class ShortCodeGenerator {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 7;
    private static final SecureRandom random = new SecureRandom();

    public static String generateShortCode() {
        byte[] bytes = new byte[CODE_LENGTH];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}