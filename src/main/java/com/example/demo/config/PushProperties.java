package com.example.demo.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

// application.properties의 app.push.* 설정 (S24-b). 휴대폰 푸시 알림을 누가 보낼지 정한다.
// mode: off(기본, 아무것도 안 보냄) / fcm(구글 Firebase Cloud Messaging)
// (PermitOcrProperties와 같은 모양)
@ConfigurationProperties("app.push")
public record PushProperties(
        @DefaultValue("off") String mode,
        @DefaultValue Fcm fcm) {

    public record Fcm(
            // 서비스 계정 키 파일(JSON)의 경로. 파일 안에 비밀 키가 있어서 파일 자체는 서버에만 둔다(환경변수 FCM_CREDENTIALS_FILE은 경로만 담는다)
            @DefaultValue("") String credentialsFile,
            // 전용 환경변수는 일부러 만들지 않았다(테스트만 가짜 서버 주소로 덮어씀). PushConfig가 구글 주소(https)와 내 컴퓨터(localhost 등)만 허용한다.
            @DefaultValue("https://fcm.googleapis.com") String baseUrl,
            // 알림 한 건 발송의 시간 상한. 단위 없는 숫자는 초
            @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) Duration timeout) {

        // 경로에 비밀이 섞여 있을 수 있어 로그·화면에 찍히지 않게 가린다
        @Override
        public String toString() {
            return "Fcm[credentialsFile=" + (credentialsFile == null || credentialsFile.isEmpty() ? "(없음)" : "****")
                    + ", baseUrl=" + baseUrl + ", timeout=" + timeout + "]";
        }
    }
}
