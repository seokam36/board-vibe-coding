package com.example.demo.post.domain;

// 글 말머리. 코드(enum 이름)는 DB·API에서 쓰고, label은 화면에 보여 줄 한글 이름이다.
// 값을 늘리려면 여기와 DB의 CHECK 제약(새 V 파일)을 같이 바꿔야 한다.
public enum PostCategory {
    CHAT("잡담"),
    QUESTION("질문"),
    CAMPUS_LIFE("학교생활");

    private final String label;

    PostCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
