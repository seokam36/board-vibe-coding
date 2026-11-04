package com.example.demo.user.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// 문자열이 UTF-8로 인코딩했을 때 value바이트 이하인지 검사한다.
// BCrypt는 72바이트를 넘으면 예외(500)를 내므로, 미리 400으로 막기 위해 쓴다. (한글은 글자당 3바이트)
@Documented
@Constraint(validatedBy = Utf8MaxBytesValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8MaxBytes {

    int value();

    String message() default "UTF-8 기준 {value}바이트를 넘을 수 없습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
