package com.example.demo.market.domain;

// 사진 검사 결과. 판정과, 그 판정을 만든 항목·등급(자동 신고 글 "성인물 LIKELY" 같은 것에 쓴다).
// category·likelihood는 BLOCK/FLAG 때만 값이 있고 나머지는 null이다. 사진 내용은 담지 않는다.
public record ImageSafetyResult(SafetyVerdict verdict, SafetyCategory category, SafeSearchLikelihood likelihood) {

    private static final ImageSafetyResult UNCHECKED = new ImageSafetyResult(SafetyVerdict.UNCHECKED, null, null);
    private static final ImageSafetyResult PASS = new ImageSafetyResult(SafetyVerdict.PASS, null, null);

    public static ImageSafetyResult unchecked() {
        return UNCHECKED;
    }

    public static ImageSafetyResult pass() {
        return PASS;
    }

    // 로그·신고 글에 쓸 짧은 설명 (예: "ADULT LIKELY"). 항목이 없으면 판정 이름만.
    public String describe() {
        // category·likelihood 중 하나라도 비어 있으면 NPE가 나지 않게 판정 이름만 쓴다
        return category == null || likelihood == null ? verdict.name() : category.name() + " " + likelihood.name();
    }
}
