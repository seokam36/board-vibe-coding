package com.example.demo.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

// application.properties의 app.market.safesearch.* 설정 (S30-a). 중고 글 사진을 누가 검사할지 정한다.
// mode: off(기본, 검사 안 함 = 통과) / vision(구글 Cloud Vision SafeSearch)
// 기준 등급 값은 VERY_UNLIKELY·UNLIKELY·POSSIBLE·LIKELY·VERY_LIKELY 또는 OFF(그 기준 안 씀). 잘못된 값이면 시작 실패.
@ConfigurationProperties("app.market.safesearch")
public record MarketSafeSearchProperties(
        @DefaultValue("off") String mode,
        // 비밀 값(허가서 판독과 같은 VISION_API_KEY를 쓴다). 기본은 빈 값이라 vision인데 키가 없으면 시작 때 막는다.
        @DefaultValue("") String apiKey,
        // 가짜 서버 주소는 테스트만 덮어쓴다. MarketSafeSearchConfig가 구글 주소(https)와 내 컴퓨터만 허용한다.
        @DefaultValue("https://vision.googleapis.com") String baseUrl,
        // 사진 한 장 검사의 전체 상한. 단위 없는 숫자는 초
        @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) Duration timeout,
        // 구글을 동시에 부르는 최대 수. 가득 차면 기다리지 않고 바로 UNCHECKED
        @DefaultValue("2") int maxConcurrent,
        // 이보다 큰 사진은 구글을 부르지 않는다
        @DefaultValue("7MB") DataSize maxImageSize,
        // 서버 전체 하루 구글 호출 상한(서울 날짜). 0이면 상한 끔, 음수면 시작 실패
        @DefaultValue("30") int perDay,
        @DefaultValue Rules adult,
        @DefaultValue Rules violence,
        @DefaultValue Rules racy) {

    // 항목 하나의 막기/신고 기준. 실제 기본값은 application.properties가 정한다(여기 기본은 OFF)
    public record Rules(@DefaultValue("OFF") String block, @DefaultValue("OFF") String flag) {
    }

    // 키가 로그에 나오지 않게 가려서 찍는다
    @Override
    public String toString() {
        return "MarketSafeSearchProperties[mode=" + mode + ", apiKey="
                + (apiKey == null || apiKey.isEmpty() ? "(없음)" : "****") + "]";
    }
}
