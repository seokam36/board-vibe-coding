package com.example.demo.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.permit.service.NoopPermitTextReader;
import com.example.demo.permit.service.PermitTextReader;
import com.example.demo.permit.service.VisionPermitTextReader;

// PERMIT_OCR_MODE 설정으로 허가서 글자 판독기를 갈아 끼운다: off(기본) = 항상 못 읽음, vision = 구글 Cloud Vision (S20-e2). LlmConfig와 같은 방식.
// 이 클래스의 생성자가 시작 때 모드와 설정 값을 검사한다. 이상하면 어떤 변수가 문제인지 알려 주며 시작이 실패한다(값 자체는 출력 안 함. 키가 새지 않게).
// 구글에 닿지 않아도 시작은 성공한다(연결은 신청이 들어올 때 처음 한다).
//
// 참고(리뷰 결정): 호출하는 쪽(PermitAutoReview)은 RuntimeException만 잡는다. 판독기는 모든 실패를 빈 값으로 돌려주지만,
// 스레드를 못 만드는 메모리 부족 같은 Error가 호출 스레드에서 나면 그 신청은 500이 된다(신청 행은 PENDING으로 이미 남아 있다).
// Error를 널리 삼키면 진짜 서버 문제까지 숨기므로 일부러 잡지 않는다.
@Configuration
@EnableConfigurationProperties(PermitOcrProperties.class)
public class PermitOcrConfig {

    private static final Logger log = LoggerFactory.getLogger(PermitOcrConfig.class);

    // 키를 실어 보내는 주소라 구글 호스트 하나와 내 컴퓨터 안(테스트의 가짜 서버)만 허락한다
    private static final String VISION_HOST = "vision.googleapis.com";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public PermitOcrConfig(PermitOcrProperties props) {
        String mode = props.mode();
        if (!"off".equals(mode) && !"vision".equals(mode)) {
            throw new IllegalStateException("PERMIT_OCR_MODE는 off, vision 중 하나여야 합니다 (현재 값은 출력하지 않음).");
        }
        if ("off".equals(mode)) {
            // 시작할 때 한 번만 알린다
            log.info("허가서 글자 판독 꺼짐(PERMIT_OCR_MODE=off): 모든 신청은 관리자 대기로 남습니다.");
        } else {
            checkVision(props.vision());
            // 시작 로그 한 줄: 키·주소는 출력하지 않는다(숫자 설정만)
            log.info("허가서 판독기: vision(Google Cloud Vision) 켜짐, 시간 상한 {}초, 동시 {}건",
                    props.vision().timeout().toSeconds(), props.vision().maxConcurrent());
        }
    }

    // vision 모드 설정 검사. 메시지에는 변수 이름만 쓰고 값(특히 키)은 절대 쓰지 않는다.
    private static void checkVision(PermitOcrProperties.Vision v) {
        String key = v.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("PERMIT_OCR_MODE=vision인데 VISION_API_KEY가 비어 있습니다.");
        }
        // 공백·줄바꿈 같은 글자가 끼면(.env 복사 실수) 헤더로 못 보낸다. 호출 때 터지기 전에 시작 때 막는다.
        if (key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalStateException("VISION_API_KEY에 공백이나 줄바꿈 같은 글자가 들어 있습니다.");
        }
        Duration timeout = v.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("VISION_TIMEOUT은 0보다 커야 합니다 (단위 없으면 초, 예: 10 또는 10s).");
        }
        if (v.maxConcurrent() < 1) {
            throw new IllegalStateException("app.permit.ocr.vision.max-concurrent는 1 이상이어야 합니다.");
        }
        if (v.maxImageSize() == null || v.maxImageSize().toBytes() < 1) {
            throw new IllegalStateException("app.permit.ocr.vision.max-image-size는 0보다 커야 합니다.");
        }
        checkBaseUrl(v.baseUrl());
    }

    // 키를 헤더에 실어 보내는 주소다. 엉뚱한 곳으로 보내면 키가 샌다: 구글 호스트(https, 기본 포트)와 내 컴퓨터(가짜 서버)만 허용한다.
    private static void checkBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.permit.ocr.vision.base-url이 올바른 주소가 아닙니다.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean httpOrHttps = "http".equals(scheme) || "https".equals(scheme);
        // (a) 진짜 구글 주소: https + 정확히 이 호스트 + 포트 기본(없음 또는 443)
        boolean google = "https".equals(scheme) && VISION_HOST.equals(host) && (uri.getPort() == -1 || uri.getPort() == 443);
        // (b) 내 컴퓨터(가짜 서버): http·https 모두, 포트 자유
        boolean local = httpOrHttps && host != null && LOCAL_HOSTS.contains(host);
        // 사용자 정보(abc@호스트)가 낀 주소는 위장에 쓰일 수 있어 거절한다
        if (uri.getUserInfo() != null || !(google || local)) {
            throw new IllegalStateException("app.permit.ocr.vision.base-url은 https://vision.googleapis.com 이거나 내 컴퓨터(localhost) 주소여야 합니다.");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.permit.ocr.mode", havingValue = "off", matchIfMissing = true)
    PermitTextReader noopPermitTextReader() {
        return new NoopPermitTextReader();
    }

    // destroyMethod: 앱이 꺼질 때 close()를 불러 호출 스레드와 연결을 정리한다
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.permit.ocr.mode", havingValue = "vision")
    VisionPermitTextReader visionPermitTextReader(PermitOcrProperties props) {
        PermitOcrProperties.Vision v = props.vision();
        return new VisionPermitTextReader(v.baseUrl(), v.apiKey(), v.timeout(), v.maxConcurrent(), v.maxImageSize().toBytes());
    }
}
