package com.example.demo.school.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.school.dto.SchoolDetailResponse;
import com.example.demo.school.service.SchoolService;

import lombok.RequiredArgsConstructor;

// 학교 목록 조회 (GET /schools, 로그인 없이 공개)
@RestController
@RequestMapping("/schools")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolService schoolService;

    @GetMapping
    public List<SchoolDetailResponse> getAll() {
        return schoolService.getAll();
    }
}
