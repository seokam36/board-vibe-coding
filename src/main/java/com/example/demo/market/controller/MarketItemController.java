package com.example.demo.market.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.TradeType;
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

    // 목록 조회 (최신순, 공개). 세 파라미터는 선택 필터. 숫자·코드 형식이 틀리면 스프링이 400을 돌려준다.
    @GetMapping
    public List<MarketItemResponse> getAll(@RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) TradeType tradeType,
            @RequestParam(required = false) ItemStatus status) {
        return marketItemService.getAll(schoolId, tradeType, status);
    }

    // 수정: 제목·본문·거래 방식·가격을 새 값으로 바꾸고 200 + 수정된 글을 돌려준다 (판매자 본인만)
    @PutMapping("/{id}")
    public MarketItemResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody MarketItemRequest request) {
        return marketItemService.update(id, LoginUser.from(jwt), request);
    }

    // 삭제: 성공하면 본문 없이 204 (판매자 본인 또는 관리자)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        marketItemService.delete(id, LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
