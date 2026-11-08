package com.example.demo.user.domain;

// 회원 역할. USER = 일반 회원, ADMIN = 관리자(DB에서만 지정한다. 가입으로는 만들 수 없다)
public enum UserRole {
    USER, ADMIN
}
