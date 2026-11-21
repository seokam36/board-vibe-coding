package com.example.demo.visa.repository;

import java.time.LocalDate;

// 푸시 알림 후보 한 명: 회원 id와 그 회원의 체류 만료일
public record VisaPushTarget(Long userId, LocalDate expiryDate) {
}
