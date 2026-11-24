package com.example.demo.market.service;

import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;

import java.util.Map;

// 등급 -> 판정을 정하는 순수 로직(구글·설정을 모른다). 항목마다 "막기 기준"과 "신고 기준" 등급을 받는다.
// 기준이 null이면 그 기준은 쓰지 않는다(설정 OFF). 여러 항목 중 가장 센 판정을 택한다.
// 기준이 켜진 항목의 등급이 UNKNOWN(응답에서 빠짐)이면 "검사 못 함"이라, BLOCK/FLAG가 안 나온 경우 PASS 대신 UNCHECKED로 돌려준다.
public class ImageSafetyJudge {

    // 한 항목의 기준. block 이상이면 BLOCK, flag 이상이면 FLAG. 둘 다 null이면 그 항목은 안 본다.
    public record Rule(SafeSearchLikelihood block, SafeSearchLikelihood flag) {
    }

    private final Map<SafetyCategory, Rule> rules;

    public ImageSafetyJudge(Map<SafetyCategory, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public ImageSafetyResult judge(Map<SafetyCategory, SafeSearchLikelihood> levels) {
        ImageSafetyResult best = ImageSafetyResult.pass();
        // 기준이 켜진 항목 중 등급을 모르는 것이 있었는지
        boolean unknownSeen = false;
        // enum 선언 순서(성인물 -> 폭력 -> 선정성)로 돌아서, 같은 세기면 앞 항목이 이긴다
        for (SafetyCategory category : SafetyCategory.values()) {
            Rule rule = rules.get(category);
            SafeSearchLikelihood level = levels.getOrDefault(category, SafeSearchLikelihood.UNKNOWN);
            if (rule == null) {
                continue;
            }
            if (level == SafeSearchLikelihood.UNKNOWN && (rule.block() != null || rule.flag() != null)) {
                unknownSeen = true;
            }
            SafetyVerdict verdict = SafetyVerdict.PASS;
            if (rule.block() != null && level.compareTo(rule.block()) >= 0) {
                verdict = SafetyVerdict.BLOCK;
            } else if (rule.flag() != null && level.compareTo(rule.flag()) >= 0) {
                verdict = SafetyVerdict.FLAG;
            }
            if (isStronger(verdict, level, best)) {
                best = new ImageSafetyResult(verdict, category, level);
            }
        }
        // BLOCK/FLAG가 나왔으면 그게 우선. 아무것도 안 걸렸는데 모르는 항목이 있으면 통과 확신이 없으니 UNCHECKED
        if (best.verdict() == SafetyVerdict.PASS && unknownSeen) {
            return ImageSafetyResult.unchecked();
        }
        return best;
    }

    // 판정이 더 세거나, 판정이 같고 등급이 더 센 경우만 true
    private static boolean isStronger(SafetyVerdict verdict, SafeSearchLikelihood level, ImageSafetyResult best) {
        if (verdict == SafetyVerdict.PASS) {
            return false;
        }
        if (best.verdict() == SafetyVerdict.PASS) {
            return true;
        }
        if (verdict != best.verdict()) {
            return verdict == SafetyVerdict.BLOCK;
        }
        return level.compareTo(best.likelihood()) > 0;
    }
}
