package com.example.demo.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// 여러 서비스가 같이 쓰는 오류를 한 곳에서 만든다. (던지는 건 호출한 쪽: throw ApiErrors.postNotFound())
public final class ApiErrors {

    private ApiErrors() {
    }

    // 없는 글 -> 404
    public static ResponseStatusException postNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
    }

    // 없는 댓글 -> 404
    public static ResponseStatusException commentNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다.");
    }

    // 로그인 안 됨(토큰은 유효하지만 회원이 없는 경우 포함) -> 401
    public static ResponseStatusException loginRequired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }
}
