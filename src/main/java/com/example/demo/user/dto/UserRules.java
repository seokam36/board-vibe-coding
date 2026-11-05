package com.example.demo.user.dto;

// 가입과 변경 API가 같은 입력 규칙을 쓰도록 모아 둔 상수. (어노테이션 값은 상수여야 해서 클래스로 뺐다)
public final class UserRules {

    private UserRules() {
    }

    // 닉네임: 한글·영문·숫자·밑줄 2~20자, 공백 불가 (길이도 이 패턴이 함께 검사한다)
    public static final String NICKNAME_REGEX = "[가-힣a-zA-Z0-9_]{2,20}";
    public static final String NICKNAME_MESSAGE = "닉네임은 한글·영문·숫자·밑줄 2~20자여야 합니다";

    // 비밀번호: 8~64자, UTF-8 72바이트 이하(BCrypt 한계)
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 64;
    public static final int PASSWORD_MAX_BYTES = 72;
}
