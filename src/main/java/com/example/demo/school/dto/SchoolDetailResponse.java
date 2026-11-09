package com.example.demo.school.dto;

import java.util.List;

import com.example.demo.school.domain.School;

// 학교 목록(GET /schools) 응답 {id, name, emailDomains}. 글 응답의 school은 도메인이 필요 없어 SchoolResponse를 그대로 쓴다.
public record SchoolDetailResponse(Long id, String name, List<String> emailDomains) {

    public static SchoolDetailResponse of(School school, List<String> emailDomains) {
        return new SchoolDetailResponse(school.getId(), school.getName(), emailDomains);
    }
}
