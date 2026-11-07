package com.example.demo.post.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.dto.CategoryResponse;

// 말머리 목록 조회 (GET /categories, 로그인 없이 공개). enum 고정값이라 DB 조회 없이 바로 돌려준다.
@RestController
@RequestMapping("/categories")
public class CategoryController {

    @GetMapping
    public List<CategoryResponse> getAll() {
        return Arrays.stream(PostCategory.values()).map(CategoryResponse::from).toList();
    }
}
