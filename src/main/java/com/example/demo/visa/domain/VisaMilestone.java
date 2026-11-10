package com.example.demo.visa.domain;

// 비자 만료 전에 확인할 시점(마일스톤). "만료 며칠 전"과 할 일 문구를 한 곳에 모아 둔다.
// 시점 숫자 120·30·7과 문구는 여기서만 바꾸면 된다(하이코리아 확인 후 조정 가능). 선언 순서 = 먼 시점부터.
// 이름(PREPARE·CHECK·URGENT)은 식별자라 기준 숫자를 바꿔도 이름은 그대로 둔다.
// 문구는 "신청해야 합니다" 같은 지시·판단 대신 "확인하세요"로 쓴다(개인 사정을 서버가 모르므로).
public enum VisaMilestone {
    PREPARE(120, "체류 기간 연장·자격 변경 준비 — 필요한 서류와 신청 시기를 하이코리아에서 확인하세요"),
    CHECK(30, "신청 확인 — 연장이나 자격 변경을 할 계획이라면 하이코리아에서 방법을 확인하세요"),
    URGENT(7, "만료 임박 — 체류 상태를 하이코리아나 관할 출입국·외국인청에서 확인하세요");

    private final int daysBefore; // 만료일 며칠 전인가
    private final String title;   // 화면에 보일 할 일 문구

    VisaMilestone(int daysBefore, String title) {
        this.daysBefore = daysBefore;
        this.title = title;
    }

    public int getDaysBefore() {
        return daysBefore;
    }

    public String getTitle() {
        return title;
    }
}
