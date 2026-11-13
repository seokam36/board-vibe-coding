package com.example.demo.message.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 대화 보기 두 API(일반 대화 보기, 관리자 신고 대화 읽기)가 같이 쓰는 "최근 쪽지만 읽기" 규칙 (S14-g).
// 왜: 방의 쪽지를 전부 메모리로 읽으면 쪽지가 아주 많은 방을 열 때 앱 힙이 모자랄 수 있다.
public final class RecentMessages {

    // 한 번에 보여 주는 최신 쪽지 수. 두 서비스가 이 한 곳의 값을 쓴다.
    public static final int LIMIT = 200;

    // DB에서 "하나 더" 가져오는 개수(201). 201통이 왔으면 더 오래된 쪽지가 있다는 뜻이다.
    public static final int FETCH_SIZE = LIMIT + 1;

    private RecentMessages() {
    }

    // 쪽지 목록(items)은 오름차순(오래된 것부터), hasMore는 잘려 나간 더 오래된 쪽지가 있는지.
    public record Result<T>(List<T> items, boolean hasMore) {
    }

    // id 내림차순(최신이 앞)으로 가져온 행(최대 201개)을 받아, 200개로 자르고 오름차순으로 뒤집는다.
    public static <T> Result<T> trim(List<T> newestFirst) {
        boolean hasMore = newestFirst.size() > LIMIT;
        // 201번째(가장 오래된 1통)는 버린다
        List<T> kept = new ArrayList<>(hasMore ? newestFirst.subList(0, LIMIT) : newestFirst);
        Collections.reverse(kept); // 응답은 오래된 것부터
        return new Result<>(kept, hasMore);
    }
}
