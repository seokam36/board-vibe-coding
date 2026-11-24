package com.example.demo.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// S30-b 리뷰 반영: Report.appendAutoDetail 단위 검사 (DB 없이).
class ReportAppendAutoDetailTests {

    private static Report 자동신고() {
        return Report.ofMarketItemAuto(null, ReportReason.SEXUAL, "자동 탐지: 사진 1, 성인물 POSSIBLE");
    }

    // 무엇: 덧붙이면 " / 사진 …"이 끝에 순서대로 붙는다
    @Test
    void 덧붙이면_설명_끝에_구분자와_함께_순서대로_붙는다() {
        Report r = 자동신고();
        r.appendAutoDetail("사진 2, 폭력 LIKELY", 300);
        r.appendAutoDetail("사진 3, 선정성 VERY_LIKELY", 300);

        assertThat(r.getDetail()).isEqualTo(
                "자동 탐지: 사진 1, 성인물 POSSIBLE / 사진 2, 폭력 LIKELY / 사진 3, 선정성 VERY_LIKELY");
    }

    // 무엇/왜: DB 열이 300자라 넘으면 저장 오류가 난다. 많이 덧붙여도 300자 이하이고 " 외"로 끝나며 그 뒤로 안 늘어나야 한다.
    @Test
    void 아주_많이_덧붙여도_300자를_넘지_않고_외로_끝나며_그_뒤로는_늘지_않는다() {
        Report r = 자동신고();
        for (int i = 2; i < 200; i++) {
            r.appendAutoDetail("사진 " + (1000 + i) + ", 성인물 POSSIBLE", 300);
            assertThat(r.getDetail().length()).as("덧붙인 %d번째", i).isLessThanOrEqualTo(300);
        }

        String full = r.getDetail();
        assertThat(full).endsWith(" 외");
        assertThat(full.length()).isLessThanOrEqualTo(300);

        r.appendAutoDetail("사진 99999, 성인물 POSSIBLE", 300);
        assertThat(r.getDetail()).isEqualTo(full);
    }

    // 무엇: 한 번에 넘치는 경계에서도 이어 붙이지 않고 " 외"만 붙인다
    @Test
    void 한_번에_넘치는_긴_덧붙임은_붙이지_않고_외만_붙인다() {
        Report r = 자동신고();
        String before = r.getDetail();
        r.appendAutoDetail("가".repeat(400), 300);

        assertThat(r.getDetail()).isEqualTo(before + " 외");
    }

    // 무엇/왜: 회원 신고(USER)의 설명을 시스템이 바꾸면 안 된다
    @Test
    void USER_신고에_덧붙이면_IllegalStateException이다() {
        Report user = Report.ofMarketItem(null, null, ReportReason.SPAM, "광고예요");

        assertThatThrownBy(() -> user.appendAutoDetail("사진 2, 성인물 POSSIBLE", 300)).isInstanceOf(IllegalStateException.class);
        assertThat(user.getDetail()).isEqualTo("광고예요");
    }
}
