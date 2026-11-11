package com.example.demo.market.controller;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.market.dto.MarketItemPhotoContent;
import com.example.demo.market.dto.MarketItemPhotoResponse;
import com.example.demo.market.service.MarketItemPhotoService;

import lombok.RequiredArgsConstructor;

// 중고 글 사진 HTTP 요청을 받는 진입점 (/market-items/{id}/photos). 올리기(POST)·보기(GET)·지우기(DELETE).
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
        // Location은 사진 한 장 보기 주소(아래 view)
        return ResponseEntity.created(URI.create("/market-items/" + id + "/photos/" + response.id())).body(response);
    }

    // 사진 파일 보기 (공개). ?size=thumb 이면 썸네일, 없으면 본 사진, 그 외 값은 400.
    @GetMapping("/{photoId}")
    public ResponseEntity<byte[]> view(@PathVariable Long id, @PathVariable Long photoId,
            @RequestParam(required = false) String size) {
        MarketItemPhotoContent content = photoService.getPhoto(id, photoId, size);
        return ResponseEntity.ok()
                // 종류는 DB에 적어 둔 값을 그대로 쓴다 (image/jpeg 또는 image/png)
                .contentType(MediaType.parseMediaType(content.contentType()))
                // 1년 동안 브라우저·중간 서버가 저장해 두고 다시 묻지 않아도 된다(immutable).
                // 사진 id의 그림은 한 번 올리면 절대 바뀌지 않기 때문이다(고치려면 지우고 새로 올려 새 id를 받는다).
                // 이 헤더를 직접 넣으면 Spring Security가 기본으로 붙이는 "no-cache" 계열 헤더는 붙지 않는다.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                // 브라우저가 내용을 보고 종류를 멋대로 바꿔 읽지 못하게 한다
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes()); // Content-Length는 본문 길이로 자동으로 붙는다
    }

    // 사진 지우기: 판매자 본인만(관리자도 403). 성공하면 204
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @PathVariable Long photoId) {
        photoService.deletePhoto(id, photoId, LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
