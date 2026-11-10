package com.example.demo.visa.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.example.demo.visa.domain.VisaMilestone;
import com.example.demo.visa.domain.VisaPlanStage;

// 내 비자 일정 응답: 만료일 + 남은 일수 + 띠 단계 + 할 일 체크리스트. 저장하지 않고 매번 "오늘" 기준으로 계산한다.
// 일정이 없으면 expiryDate·daysLeft·stage가 null, todos는 빈 배열(404가 아니라 200).
public record VisaPlanResponse(LocalDate expiryDate, Integer daysLeft, VisaPlanStage stage, List<Todo> todos) {

    // 할 일 한 줄. dueDate = 만료일 - N일, daysLeft = dueDate까지 남은 일수(지났으면 음수).
    public record Todo(String code, String title, LocalDate dueDate, int daysLeft) {
    }

    // 응답을 만드는 유일한 길(조회·저장 공통). today는 서비스가 요청당 한 번 구해 넘긴다(요청 중 날짜가 바뀌지 않게).
    // expiryDate가 null이면 "일정 없음" 응답.
    public static VisaPlanResponse of(LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null) {
            return new VisaPlanResponse(null, null, null, List.of());
        }
        long daysLeft = ChronoUnit.DAYS.between(today, expiryDate);
        // 지난 할 일도 빼지 않고 daysLeft를 음수로 둔다. dueDate 오름차순(= 먼 시점부터)
        List<Todo> todos = Arrays.stream(VisaMilestone.values())
                .map(m -> {
                    LocalDate due = expiryDate.minusDays(m.getDaysBefore());
                    return new Todo(m.name(), m.getTitle(), due, Math.toIntExact(ChronoUnit.DAYS.between(today, due)));
                })
                .sorted(Comparator.comparing(Todo::dueDate))
                .toList();
        return new VisaPlanResponse(expiryDate, Math.toIntExact(daysLeft), VisaPlanStage.of(daysLeft), todos);
    }
}
