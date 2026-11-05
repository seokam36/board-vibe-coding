package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 회원가입 요청 본문. 잘못된 입력은 @Valid 검증에서 400으로 걸러진다.
public record SignupRequest(
        // 소문자·숫자·밑줄 4~20자 (길이도 이 패턴이 함께 검사한다)
        @NotBlank @Pattern(regexp = "[a-z0-9_]{4,20}", message = "아이디는 소문자·숫자·밑줄 4~20자여야 합니다") String username,
        // 한글·영문·숫자·밑줄 2~20자, 공백 불가, 대소문자 구분 (길이도 이 패턴이 함께 검사한다)
        @NotBlank @Pattern(regexp = "[가-힣a-zA-Z0-9_]{2,20}", message = "닉네임은 한글·영문·숫자·밑줄 2~20자여야 합니다") String nickname,
        // 8~64자, 공백만은 불가, UTF-8 72바이트 이하(BCrypt 한계)
        @NotBlank @Size(min = 8, max = 64) @Utf8MaxBytes(72) String password) {

    // record 기본 toString은 비밀번호를 그대로 찍으므로, 로그에 새지 않게 가린다
    @Override
    public String toString() {
        return "SignupRequest[username=" + username + ", nickname=" + nickname + ", password=***]";
    }
}
