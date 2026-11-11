package com.example.demo.market.controller;

import java.net.URI;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.market.dto.MarketItemPhotoResponse;
import com.example.demo.market.service.MarketItemPhotoService;

import lombok.RequiredArgsConstructor;

// 중고 글 사진 HTTP 요청을 받는 진입점 (/market-items/{id}/photos). 지금은 올리기만 있다(보기·삭제는 S13-g3).
@RestController
@RequestMapping("/market-items/{id}/photos")
@RequiredArgsConstructor
public class MarketItemPhotoController {

    private final MarketItemPhotoService photoService;

    // 사진 올리기: multipart 요청의 파트 이름 "file" 한 장. 201 Created + Location. 판매자 본인만(관리자도 403).
    // multipart가 아닌 요청은 스프링이 415, 파트가 없으면 400으로 처리한다. 너무 큰 파일은 413(application.properties 한도).
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MarketItemPhotoResponse> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestPart("file") MultipartFile file) {
        MarketItemPhotoResponse response = photoService.upload(id, LoginUser.from(jwt), file);
        // Location은 사진 한 장 보기 주소(GET은 S13-g3에서 만든다)
        return ResponseEntity.created(URI.create("/market-items/" + id + "/photos/" + response.id())).body(response);
    }
}
