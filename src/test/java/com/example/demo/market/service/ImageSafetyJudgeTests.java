package com.example.demo.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;
import com.example.demo.market.service.ImageSafetyJudge.Rule;

// S30-a: 판정기(등급 -> PASS/FLAG/BLOCK)를 스프링·구글 없이 순수하게 검사한다.
// 기준은 application.properties 기본값과 같다: 성인물 막기 VERY_LIKELY/신고 POSSIBLE, 폭력 VERY_LIKELY/LIKELY, 선정성 막기 OFF/신고 VERY_LIKELY.
class ImageSafetyJudgeTests {

    private static ImageSafetyJudge 기본판정기() {
        Map<SafetyCategory, Rule> rules = new EnumMap<>(SafetyCategory.class);
        rules.put(SafetyCategory.ADULT, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.POSSIBLE));
        rules.put(SafetyCategory.VIOLENCE, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.LIKELY));
        rules.put(SafetyCategory.RACY, new Rule(null, SafeSearchLikelihood.VERY_LIKELY));
        return new ImageSafetyJudge(rules);
    }

    // 항목별 등급을 만든다. 안 적은 항목은 VERY_UNLIKELY
    private static Map<SafetyCategory, SafeSearchLikelihood> 등급(SafeSearchLikelihood adult, SafeSearchLikelihood violence,
            SafeSearchLikelihood racy) {
        Map<SafetyCategory, SafeSearchLikelihood> m = new EnumMap<>(SafetyCategory.class);
        m.put(SafetyCategory.ADULT, adult);
        m.put(SafetyCategory.VIOLENCE, violence);
        m.put(SafetyCategory.RACY, racy);
        return m;
    }

    private static final SafeSearchLikelihood VU = SafeSearchLikelihood.VERY_UNLIKELY;

    // 완료 조건 3
    @Test
    void 성인물_VERY_LIKELY는_BLOCK_LIKELY는_FLAG_전부_VERY_UNLIKELY는_PASS() {
        ImageSafetyJudge j = 기본판정기();
        assertThat(j.judge(등급(SafeSearchLikelihood.VERY_LIKELY, VU, VU)).verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(j.judge(등급(SafeSearchLikelihood.LIKELY, VU, VU)).verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(j.judge(등급(VU, VU, VU)).verdict()).isEqualTo(SafetyVerdict.PASS);
    }

    // 완료 조건 4
    @Test
    void 선정성은_VERY_LIKELY만_FLAG이고_LIKELY는_PASS() {
        ImageSafetyJudge j = 기본판정기();
        ImageSafetyResult flag = j.judge(등급(VU, VU, SafeSearchLikelihood.VERY_LIKELY));
        assertThat(flag.verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(flag.category()).isEqualTo(SafetyCategory.RACY);
        assertThat(j.judge(등급(VU, VU, SafeSearchLikelihood.LIKELY)).verdict()).isEqualTo(SafetyVerdict.PASS);
    }

    @Test
    void 경계_등급_바로_아래는_넘지_않고_바로_그_등급은_넘는다() {
        ImageSafetyJudge j = 기본판정기();
        // 성인물 신고 기준 POSSIBLE: UNLIKELY는 PASS, POSSIBLE은 FLAG
        assertThat(j.judge(등급(SafeSearchLikelihood.UNLIKELY, VU, VU)).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(j.judge(등급(SafeSearchLikelihood.POSSIBLE, VU, VU)).verdict()).isEqualTo(SafetyVerdict.FLAG);
        // 폭력 신고 기준 LIKELY: POSSIBLE은 PASS, LIKELY는 FLAG, VERY_LIKELY는 BLOCK
        assertThat(j.judge(등급(VU, SafeSearchLikelihood.POSSIBLE, VU)).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(j.judge(등급(VU, SafeSearchLikelihood.LIKELY, VU)).verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(j.judge(등급(VU, SafeSearchLikelihood.VERY_LIKELY, VU)).verdict()).isEqualTo(SafetyVerdict.BLOCK);
    }

    @Test
    void 결과에_판정을_만든_항목과_등급이_담긴다() {
        ImageSafetyResult r = 기본판정기().judge(등급(SafeSearchLikelihood.LIKELY, VU, VU));
        assertThat(r.category()).isEqualTo(SafetyCategory.ADULT);
        assertThat(r.likelihood()).isEqualTo(SafeSearchLikelihood.LIKELY);
        assertThat(r.describe()).isEqualTo("ADULT LIKELY");
    }

    // 추가: 여러 항목 중 가장 센 판정
    @Test
    void 여러_항목이_걸리면_가장_센_판정이_이긴다() {
        ImageSafetyJudge j = 기본판정기();
        // 선정성 FLAG + 폭력 BLOCK -> BLOCK(폭력)
        ImageSafetyResult r = j.judge(등급(VU, SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.VERY_LIKELY));
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.category()).isEqualTo(SafetyCategory.VIOLENCE);
        // 성인물 FLAG(LIKELY) + 선정성 FLAG(VERY_LIKELY) -> 둘 다 FLAG라서 등급이 더 센 선정성이 이긴다
        ImageSafetyResult r2 = j.judge(등급(SafeSearchLikelihood.LIKELY, VU, SafeSearchLikelihood.VERY_LIKELY));
        assertThat(r2.verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(r2.category()).isEqualTo(SafetyCategory.RACY);
    }

    // 추가: OFF(null)면 그 기준은 안 쓴다
    @Test
    void 기준이_OFF면_그_기준은_쓰지_않는다() {
        Map<SafetyCategory, Rule> rules = new EnumMap<>(SafetyCategory.class);
        // 막기만 끔: 신고 기준만 남는다
        rules.put(SafetyCategory.ADULT, new Rule(null, SafeSearchLikelihood.POSSIBLE));
        // 신고만 끔: 막기 기준만 남는다
        rules.put(SafetyCategory.VIOLENCE, new Rule(SafeSearchLikelihood.VERY_LIKELY, null));
        // 둘 다 끔: 그 항목은 아예 안 본다
        rules.put(SafetyCategory.RACY, new Rule(null, null));
        ImageSafetyJudge j = new ImageSafetyJudge(rules);

        assertThat(j.judge(등급(SafeSearchLikelihood.VERY_LIKELY, VU, VU)).verdict()).as("막기 OFF면 최고 등급도 FLAG까지").isEqualTo(SafetyVerdict.FLAG);
        assertThat(j.judge(등급(VU, SafeSearchLikelihood.LIKELY, VU)).verdict()).as("신고 OFF면 LIKELY는 PASS").isEqualTo(SafetyVerdict.PASS);
        assertThat(j.judge(등급(VU, SafeSearchLikelihood.VERY_LIKELY, VU)).verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(j.judge(등급(VU, VU, SafeSearchLikelihood.VERY_LIKELY)).verdict()).as("둘 다 OFF").isEqualTo(SafetyVerdict.PASS);
    }

    @Test
    void 모르는_등급_글자는_UNKNOWN이고_기준이_켜진_항목이_UNKNOWN이면_UNCHECKED이다() {
        assertThat(SafeSearchLikelihood.fromApi("엉뚱한값")).isEqualTo(SafeSearchLikelihood.UNKNOWN);
        assertThat(SafeSearchLikelihood.fromApi(null)).isEqualTo(SafeSearchLikelihood.UNKNOWN);
        assertThat(SafeSearchLikelihood.fromApi("LIKELY")).isEqualTo(SafeSearchLikelihood.LIKELY);
        assertThat(기본판정기().judge(등급(SafeSearchLikelihood.UNKNOWN, SafeSearchLikelihood.UNKNOWN, SafeSearchLikelihood.UNKNOWN))
                .verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
    }

    // 리뷰 5: UNKNOWN이 있어도 다른 항목의 BLOCK/FLAG가 우선
    @Test
    void UNKNOWN과_다른_항목_FLAG면_FLAG() {
        ImageSafetyResult r = 기본판정기().judge(등급(SafeSearchLikelihood.UNKNOWN, VU, SafeSearchLikelihood.VERY_LIKELY));
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(r.category()).isEqualTo(SafetyCategory.RACY);
    }

    @Test
    void UNKNOWN과_다른_항목_BLOCK이면_BLOCK() {
        ImageSafetyResult r = 기본판정기().judge(등급(SafeSearchLikelihood.UNKNOWN, SafeSearchLikelihood.VERY_LIKELY, VU));
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.category()).isEqualTo(SafetyCategory.VIOLENCE);
    }

    @Test
    void 응답에서_빠진_항목도_UNKNOWN으로_보고_BLOCK_FLAG가_없으면_UNCHECKED() {
        Map<SafetyCategory, SafeSearchLikelihood> m = new EnumMap<>(SafetyCategory.class);
        m.put(SafetyCategory.VIOLENCE, VU);
        m.put(SafetyCategory.RACY, VU);
        assertThat(기본판정기().judge(m).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
    }

    @Test
    void 기준이_꺼진_항목만_UNKNOWN이고_나머지가_안전하면_PASS() {
        // 선정성은 막기 OFF지만 신고 기준이 켜져 있으니 완전히 끈 항목으로 확인한다
        Map<SafetyCategory, Rule> rules = new EnumMap<>(SafetyCategory.class);
        rules.put(SafetyCategory.ADULT, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.POSSIBLE));
        rules.put(SafetyCategory.VIOLENCE, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.LIKELY));
        rules.put(SafetyCategory.RACY, new Rule(null, null));
        ImageSafetyJudge j = new ImageSafetyJudge(rules);
        assertThat(j.judge(등급(VU, VU, SafeSearchLikelihood.UNKNOWN)).verdict()).isEqualTo(SafetyVerdict.PASS);
        // 기준 자체가 없는(맵에 없는) 항목의 UNKNOWN도 무시
        Map<SafetyCategory, Rule> noRacy = new EnumMap<>(SafetyCategory.class);
        noRacy.put(SafetyCategory.ADULT, rules.get(SafetyCategory.ADULT));
        noRacy.put(SafetyCategory.VIOLENCE, rules.get(SafetyCategory.VIOLENCE));
        assertThat(new ImageSafetyJudge(noRacy).judge(등급(VU, VU, SafeSearchLikelihood.UNKNOWN)).verdict()).isEqualTo(SafetyVerdict.PASS);
    }

    // 리뷰 9: category나 likelihood가 null이어도 describe가 안 터진다
    @Test
    void describe는_항목이나_등급이_null이어도_판정_이름만_돌려준다() {
        assertThat(new ImageSafetyResult(SafetyVerdict.FLAG, null, SafeSearchLikelihood.LIKELY).describe()).isEqualTo("FLAG");
        assertThat(new ImageSafetyResult(SafetyVerdict.FLAG, SafetyCategory.ADULT, null).describe()).isEqualTo("FLAG");
        assertThat(new ImageSafetyResult(SafetyVerdict.BLOCK, null, null).describe()).isEqualTo("BLOCK");
        assertThat(ImageSafetyResult.pass().describe()).isEqualTo("PASS");
        assertThat(ImageSafetyResult.unchecked().describe()).isEqualTo("UNCHECKED");
    }

    @Test
    void 꺼진_검사기는_항상_UNCHECKED이다() {
        assertThat(new NoopImageSafetyChecker().check(new byte[] { 1, 2, 3 }).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
    }
}
