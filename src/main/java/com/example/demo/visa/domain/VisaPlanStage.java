package com.example.demo.visa.domain;

import java.util.Arrays;
import java.util.Comparator;

// 상단 띠의 단계. 마일스톤 단계는 대응하는 VisaMilestone을 필드로 직접 들고(이름 문자열 매칭 안 함),
// EXPIRED는 만료일이 지난 경우라 마일스톤이 없다(null).
// 일정이 없거나 아직 멀면(가장 먼 시점보다 남음) 단계 자체가 없다(null).
public enum VisaPlanStage {
    PREPARE(VisaMilestone.PREPARE),
    CHECK(VisaMilestone.CHECK),
    URGENT(VisaMilestone.URGENT),
    EXPIRED(null);

    private final VisaMilestone milestone; // 이 단계가 따라가는 마일스톤 (EXPIRED는 null)

    VisaPlanStage(VisaMilestone milestone) {
        this.milestone = milestone;
    }

    public VisaMilestone getMilestone() {
        return milestone;
    }

    // 남은 일수로 단계를 계산하는 순수 함수. 경계는 VisaMilestone의 숫자에서 도출한다(여기엔 숫자 리터럴 없음).
    // 규칙: 지난 날짜(음수) = EXPIRED, 아니면 "남은 일수 <= daysBefore"인 시점 중 가장 가까운(숫자가 작은) 것.
    public static VisaPlanStage of(long daysLeft) {
        if (daysLeft < 0) {
            return EXPIRED;
        }
        return Arrays.stream(values())
                .filter(s -> s.milestone != null && daysLeft <= s.milestone.getDaysBefore())
                .min(Comparator.comparingInt(s -> s.milestone.getDaysBefore()))
                .orElse(null);
    }
}
