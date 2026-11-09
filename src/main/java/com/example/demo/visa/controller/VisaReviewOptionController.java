package com.example.demo.visa.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.visa.dto.VisaReviewOptionsResponse;

// 비자 후기 선택지 조회 (GET /visa-review-options, 로그인 없이 공개). enum 고정값이라 DB 조회 없이 바로 돌려준다.
@RestController
@RequestMapping("/visa-review-options")
public class VisaReviewOptionController {

    @GetMapping
    public VisaReviewOptionsResponse getOptions() {
        return VisaReviewOptionsResponse.create();
    }
}
