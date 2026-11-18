package com.example.demo.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

// application.properties의 app.permit.ocr.* 설정 (S20-e2). 허가서 사진의 글자를 누가 읽을지 정한다.
// mode: off(기본, 항상 못 읽음 = 전부 관리자 대기) / vision(구글 Cloud Vision)
// (LlmProperties와 같은 모양. app.permit.* 의 비밀 값 설정인 PermitProperties와는 따로 둔다)
@ConfigurationProperties("app.permit.ocr")
public record PermitOcrProperties(
        @DefaultValue("off") String mode,
        @DefaultValue Vision vision) {

    public record Vision(
            // 전용 환경변수는 일부러 만들지 않았다(테스트만 가짜 서버 주소로 덮어씀). PermitOcrConfig가 구글 주소(https)와 내 컴퓨터(localhost 등)만 허용한다.
            @DefaultValue("https://vision.googleapis.com") String baseUrl,
            // 비밀 값. 기본은 빈 값이라 vision 모드인데 키가 없으면 PermitOcrConfig가 시작 때 막는다.
            @DefaultValue("") String apiKey,
            // 사진 한 장 판독의 전체 상한. 단위 없는 숫자는 초
            @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) Duration timeout,
            // 구글을 동시에 부르는 최대 수. 가득 차면 기다리지 않고 바로 "못 읽음"
            @DefaultValue("2") int maxConcurrent,
            // 이보다 큰 사진은 구글을 부르지 않는다(요청 크기 한도 보호)
            @DefaultValue("7MB") DataSize maxImageSize) {

        // record의 기본 toString은 모든 값을 찍어서 키가 로그·화면에 나올 수 있다. 키만 가려서 찍는다.
        @Override
        public String toString() {
            return "Vision[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "(없음)" : "****")
                    + ", timeout=" + timeout + ", maxConcurrent=" + maxConcurrent + ", maxImageSize=" + maxImageSize + "]";
        }
    }
}
