package com.example.demo.permit.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletResponse;

// 관리자 허가서 심사 컨트롤러 전용 검증 실패 처리 (S20-b).
// 스프링 기본 처리기는 본문 검증이 실패하면 "rejected value [입력 원문]"이 들어간 경고(WARN)를 로그에 남긴다.
// 허가서 번호 원문이 로그에 남으면 안 되므로, 이 컨트롤러에 한해 같은 400을 로그 없이 돌려준다.
// assignableTypes로 이 컨트롤러에만 걸어서 다른 API의 검증 실패 처리(응답·로그)는 바뀌지 않는다.
// sendError(400)는 스프링 기본 처리기가 하는 일과 같아서 응답 본문 모양(/error 기본 응답)도 그대로다.
@RestControllerAdvice(assignableTypes = AdminPermitController.class)
public class AdminPermitExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    void handleInvalidBody(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }
}
