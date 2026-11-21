package com.example.demo.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.push.service.FcmPushSender;
import com.example.demo.push.service.GoogleAccessTokenProvider;
import com.example.demo.push.service.NoopPushSender;
import com.example.demo.push.service.PushSender;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

// PUSH_MODE 설정으로 푸시 발송기를 갈아 끼운다: off(기본) = 아무것도 안 보냄, fcm = 구글 FCM (S24-b). PermitOcrConfig와 같은 방식.
// 이 클래스의 생성자가 시작 때 모드와 설정 값을 검사한다. 이상하면 어떤 변수가 문제인지 알려 주며 시작이 실패한다.
// 오류 메시지에는 변수 이름만 쓴다(키 내용·파일 경로·입력값은 절대 안 씀).
// 구글에 닿지 않아도 시작은 성공한다(출입증 받기는 첫 발송 때 처음 한다).
@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    // 출입증(액세스 토큰)을 실어 보내는 주소라 구글 호스트 하나와 내 컴퓨터 안(테스트의 가짜 서버)만 허락한다
    private static final String FCM_HOST = "fcm.googleapis.com";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    // FCM 발송에만 쓰는 권한 범위
    private static final List<String> FCM_SCOPES = List.of("https://www.googleapis.com/auth/firebase.messaging");

    public PushConfig(PushProperties props) {
        String mode = props.mode();
        if (!"off".equals(mode) && !"fcm".equals(mode)) {
            throw new IllegalStateException("PUSH_MODE는 off, fcm 중 하나여야 합니다 (현재 값은 출력하지 않음).");
        }
        if ("off".equals(mode)) {
            log.info("푸시 알림 꺼짐(PUSH_MODE=off): 알림을 보내지 않습니다.");
        } else {
            checkFcm(props.fcm());
            // 시작 로그 한 줄: 키·경로·주소는 출력하지 않는다
            log.info("푸시 fcm 켜짐, 시간 상한 {}초", props.fcm().timeout().toSeconds());
        }
    }

    // fcm 모드 설정 검사 (키 파일 내용 검사는 발송기 빈을 만들 때 fcmPushSender가 한다)
    private static void checkFcm(PushProperties.Fcm fcm) {
        String file = fcm.credentialsFile();
        if (file == null || file.isBlank()) {
            throw new IllegalStateException("PUSH_MODE=fcm인데 FCM_CREDENTIALS_FILE이 비어 있습니다.");
        }
        Duration timeout = fcm.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("FCM_TIMEOUT은 0보다 커야 합니다 (단위 없으면 초, 예: 10 또는 10s).");
        }
        checkBaseUrl(fcm.baseUrl());
    }

    // 출입증을 헤더에 실어 보내는 주소다. 엉뚱한 곳으로 보내면 새 나간다: 구글 호스트(https, 기본 포트)와 내 컴퓨터(가짜 서버)만 허용한다.
    private static void checkBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.push.fcm.base-url이 올바른 주소가 아닙니다.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean httpOrHttps = "http".equals(scheme) || "https".equals(scheme);
        // (a) 진짜 구글 주소: https + 정확히 이 호스트 + 포트 기본(없음 또는 443)
        boolean google = "https".equals(scheme) && FCM_HOST.equals(host) && (uri.getPort() == -1 || uri.getPort() == 443);
        // (b) 내 컴퓨터(가짜 서버): http·https 모두, 포트 자유
        boolean local = httpOrHttps && host != null && LOCAL_HOSTS.contains(host);
        // 사용자 정보(abc@호스트)가 낀 주소는 위장에 쓰일 수 있어 거절한다
        if (uri.getUserInfo() != null || !(google || local)) {
            throw new IllegalStateException("app.push.fcm.base-url은 https://fcm.googleapis.com 이거나 내 컴퓨터(localhost) 주소여야 합니다.");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.push.mode", havingValue = "off", matchIfMissing = true)
    PushSender noopPushSender() {
        return new NoopPushSender();
    }

    // destroyMethod: 앱이 꺼질 때 close()를 불러 연결을 정리한다
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.push.mode", havingValue = "fcm")
    FcmPushSender fcmPushSender(PushProperties props) {
        PushProperties.Fcm fcm = props.fcm();
        // 키 파일을 구글 라이브러리로 한 번만 읽는다(type이 service_account가 아니면 IOException)
        ServiceAccountCredentials account;
        try (InputStream in = Files.newInputStream(Path.of(fcm.credentialsFile()))) {
            account = ServiceAccountCredentials.fromStream(in);
        } catch (IOException | RuntimeException e) {
            // 원인 예외는 일부러 안 붙인다(경로·키 내용이 메시지에 섞일 수 있다)
            throw new IllegalStateException(
                    "FCM_CREDENTIALS_FILE을 읽을 수 없습니다(파일이 없거나, 읽기 권한이 없거나, 서비스 계정 키가 아님).");
        }
        String projectId = account.getProjectId();
        if (projectId == null || !projectId.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalStateException("FCM_CREDENTIALS_FILE에 올바른 project_id가 없습니다.");
        }
        GoogleCredentials credentials = account.createScoped(FCM_SCOPES);
        return new FcmPushSender(fcm.baseUrl(), projectId, fcm.timeout(), new GoogleAccessTokenProvider(credentials));
    }
}
