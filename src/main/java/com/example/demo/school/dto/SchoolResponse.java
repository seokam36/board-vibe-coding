package com.example.demo.school.dto;

import com.example.demo.school.domain.School;

// 학교 응답 {id, name}. 엔티티를 그대로 내보내지 않고 DTO로 바꾼다.
public record SchoolResponse(Long id, String name) {

    public static SchoolResponse from(School school) {
        return new SchoolResponse(school.getId(), school.getName());
    }

    // 학교가 없으면(미인증 등) null, 있으면 DTO로 바꾼다
    public static SchoolResponse fromNullable(School school) {
        return school == null ? null : from(school);
    }
}
