package com.example.demo.school.dto;

import com.example.demo.school.domain.School;

// 학교 응답 {id, name}. 엔티티를 그대로 내보내지 않고 DTO로 바꾼다.
public record SchoolResponse(Long id, String name) {

    public static SchoolResponse from(School school) {
        return new SchoolResponse(school.getId(), school.getName());
    }
}
