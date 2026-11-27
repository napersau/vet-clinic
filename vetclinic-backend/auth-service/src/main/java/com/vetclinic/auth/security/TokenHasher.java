package com.vetclinic.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Băm SHA-256 (hex 64 ký tự) của Refresh Token: CSDL chỉ lưu giá trị băm, không lưu token gốc. */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 luôn có sẵn trong JDK", e);
        }
    }
}
