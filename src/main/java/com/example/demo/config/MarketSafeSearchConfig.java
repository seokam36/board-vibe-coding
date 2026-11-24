package com.example.demo.config;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.service.ImageSafetyChecker;
import com.example.demo.market.service.ImageSafetyJudge;
import com.example.demo.market.service.MarketSafeSearchDailyLimiter;
import com.example.demo.market.service.NoopImageSafetyChecker;
import com.example.demo.market.service.VisionImageSafetyChecker;

// MARKET_SAFESEARCH_MODE 설정으로 중고 사진 검사기를 갈아 끼운다 (S30-a): off(기본) = 검사 안 함, vision = 구글 SafeSearch.
// PermitOcrConfig와 같은 방식: 생성자가 시작 때 설정을 검사하고, 이상하면 어떤 변수가 문제인지만 알리며 시작이 실패한다(값은 출력 안 함).
@Configuration
@EnableConfigurationProperties(MarketSafeSearchProperties.class)
public class MarketSafeSearchConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketSafeSearchConfig.class);

    // 시작 때 한 번 읽은 기준표. 판정기 빈이 이걸 그대로 쓴다(두 번 읽지 않으려고)
    private final Map<SafetyCategory, ImageSafetyJudge.Rule> rules;

    public MarketSafeSearchConfig(MarketSafeSearchProperties props) {
        String mode = props.mode();
        if (!"off".equals(mode) && !"vision".equals(mode)) {
            throw new IllegalStateException("MARKET_SAFESEARCH_MODE는 off, vision 중 하나여야 합니다 (현재 값은 출력하지 않음).");
        }
        if (props.perDay() < 0) {
            throw new IllegalStateException("MARKET_SAFESEARCH_PER_DAY는 0 이상이어야 합니다 (0이면 상한 없음).");
        }
        // 기준 등급 글자도 모드와 상관없이 검사한다(오타를 일찍 잡으려고)
        this.rules = parseRules(props);
        if ("off".equals(mode)) {
            log.info("사진 검사: 꺼짐(MARKET_SAFESEARCH_MODE=off)");
            return;
        }
        String key = props.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("MARKET_SAFESEARCH_MODE=vision인데 VISION_API_KEY가 비어 있습니다.");
        }
        if (key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalStateException("VISION_API_KEY에 공백이나 줄바꿈 같은 글자가 들어 있습니다.");
        }
        if (props.timeout() == null || props.timeout().isZero() || props.timeout().isNegative()) {
            throw new IllegalStateException("MARKET_SAFESEARCH_TIMEOUT은 0보다 커야 합니다.");
        }
        if (props.slotWait() == null || props.slotWait().isNegative()) {
            throw new IllegalStateException("app.market.safesearch.slot-wait는 0 이상이어야 합니다.");
        }
        if (props.maxConcurrent() < 1) {
            throw new IllegalStateException("app.market.safesearch.max-concurrent는 1 이상이어야 합니다.");
        }
        if (props.maxImageSize() == null || props.maxImageSize().toBytes() < 1) {
            throw new IllegalStateException("app.market.safesearch.max-image-size는 0보다 커야 합니다.");
        }
        // 키를 헤더로 보내는 주소라 구글 호스트와 내 컴퓨터만 허용(허가서 설정과 같은 검사를 재사용)
        PermitOcrConfig.checkBaseUrl(props.baseUrl(), "app.market.safesearch.base-url");
        // 시작 로그 한 줄: 키·주소는 출력하지 않는다
        log.info("사진 검사: vision 켜짐, 시간 상한 {}초, 하루 상한 {}건(0=없음)", props.timeout().toSeconds(), props.perDay());
    }

    // 항목별 기준 글자를 등급으로 바꾼다. 잘못된 글자는 설정 이름만 알리고 시작 실패.
    private static Map<SafetyCategory, ImageSafetyJudge.Rule> parseRules(MarketSafeSearchProperties props) {
        Map<SafetyCategory, ImageSafetyJudge.Rule> rules = new EnumMap<>(SafetyCategory.class);
        rules.put(SafetyCategory.ADULT, parseRule("adult", props.adult()));
        rules.put(SafetyCategory.VIOLENCE, parseRule("violence", props.violence()));
        rules.put(SafetyCategory.RACY, parseRule("racy", props.racy()));
        return rules;
    }

    private static ImageSafetyJudge.Rule parseRule(String name, MarketSafeSearchProperties.Rules r) {
        return new ImageSafetyJudge.Rule(parseLevel(name + ".block", r.block()), parseLevel(name + ".flag", r.flag()));
    }

    // OFF(대소문자 무관)면 null = 그 기준 안 씀. UNKNOWN·모르는 글자는 시작 실패(값은 출력 안 함).
    private static SafeSearchLikelihood parseLevel(String setting, String text) {
        if (text == null || "OFF".equalsIgnoreCase(text.trim())) {
            return null;
        }
        try {
            SafeSearchLikelihood level = SafeSearchLikelihood.valueOf(text.trim().toUpperCase(Locale.ROOT));
            if (level != SafeSearchLikelihood.UNKNOWN) {
                return level;
            }
        } catch (IllegalArgumentException e) {
            // 아래에서 같은 실패로 처리
        }
        throw new IllegalStateException("app.market.safesearch." + setting
                + "은 OFF, VERY_UNLIKELY, UNLIKELY, POSSIBLE, LIKELY, VERY_LIKELY 중 하나여야 합니다 (현재 값은 출력하지 않음).");
    }

    @Bean
    ImageSafetyJudge imageSafetyJudge() {
        return new ImageSafetyJudge(rules);
    }

    @Bean
    @ConditionalOnProperty(name = "app.market.safesearch.mode", havingValue = "off", matchIfMissing = true)
    ImageSafetyChecker noopImageSafetyChecker() {
        return new NoopImageSafetyChecker();
    }

    // destroyMethod: 앱이 꺼질 때 close()를 불러 호출 스레드와 연결을 정리한다
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.market.safesearch.mode", havingValue = "vision")
    VisionImageSafetyChecker visionImageSafetyChecker(MarketSafeSearchProperties props, ImageSafetyJudge judge,
            MarketSafeSearchDailyLimiter limiter) {
        return new VisionImageSafetyChecker(props.baseUrl(), props.apiKey(), props.timeout(), props.maxConcurrent(), props.slotWait(),
                props.maxImageSize().toBytes(), judge, limiter::tryAcquire);
    }
}
