package com.example.demo.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

// 학교 메일의 정규화(앞뒤 공백 제거·소문자)와 SHA-256 해시(hex)를 한 곳에 모은 도우미.
// DB에는 메일 원문 대신 이 해시만 저장한다(S10-c). 정규화 규칙이 두 곳에서 달라지지 않게 여기서만 만든다.
public final class SchoolEmailHash {

    private SchoolEmailHash() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // 이미 정규화된 메일을 받아 해시한다
    public static String of(String normalizedEmail) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256은 모든 JVM이 지원
        }
    }
}
