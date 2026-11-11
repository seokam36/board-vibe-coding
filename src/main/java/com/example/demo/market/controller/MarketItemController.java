package com.example.demo.market.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.market.dto.MarketItemRequest;
import com.example.demo.market.dto.MarketItemResponse;
import com.example.demo.market.service.MarketItemService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 물건 글 HTTP 요청을 받는 진입점 (/market-items)
@RestController
@RequestMapping("/market-items")
@RequiredArgsConstructor
public class MarketItemController {

    private final MarketItemService marketItemService;

    // 올리기: 201 Created + Location 헤더로 새 글 주소를 알려준다. 학교 인증 회원만 가능.
    @PostMapping
    public ResponseEntity<MarketItemResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MarketItemRequest request) {
        MarketItemResponse response = marketItemService.create(LoginUser.from(jwt), request);
        return ResponseEntity.created(URI.create("/market-items/" + response.id())).body(response);
    }

    // 한 건 보기(누구나). 숫자가 아닌 id는 스프링이 400으로 처리한다.
    @GetMapping("/{id}")
    public MarketItemResponse get(@PathVariable Long id) {
        return marketItemService.get(id);
    }
}
