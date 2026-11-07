package com.example.demo.post.dto;

import com.example.demo.post.domain.PostCategory;

// 말머리 응답 {code, name}. code는 API에 보내는 값, name은 화면용 한글 이름.
public record CategoryResponse(String code, String name) {

    public static CategoryResponse from(PostCategory category) {
        return new CategoryResponse(category.name(), category.getLabel());
    }
}
