package com.example.demo.user.dto;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// @Utf8MaxBytes 검사기: 문자열의 UTF-8 바이트 수가 한도 이하인지 확인한다. (BCrypt 72바이트 초과 500 에러를 400으로 막기 위해)
public class Utf8MaxBytesValidator implements ConstraintValidator<Utf8MaxBytes, String> {

    private int max;

    @Override
    public void initialize(Utf8MaxBytes annotation) {
        this.max = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null 검사는 @NotBlank의 몫이라 여기서는 통과시킨다
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
