package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 챗봇 설정 클래스(ChatProperties)를 스프링 설정으로 등록하고, 시작할 때 값이 쓸 수 있는지 검사한다(S17-f2 리뷰 반영).
// 왜: max-concurrent가 0이면 자리가 없어 모든 질문이 503이 되고, slot-wait가 질문 예산(OLLAMA_TIMEOUT)보다 길면 자리만 기다리다 예산이 끝난다.
// 이상한 값이면 시작이 실패한다(LlmConfig·JwtConfig와 같은 방식). 메시지에는 설정 이름만 쓰고 값은 출력하지 않는다.
@Configuration
@EnableConfigurationProperties({ ChatProperties.class, LlmProperties.class })
public class ChatConfig {

    public ChatConfig(ChatProperties chat, LlmProperties llm) {
        if (chat.maxConcurrent() < 1) {
            throw new IllegalStateException("app.chat.max-concurrent는 1 이상이어야 합니다.");
        }
        Duration slotWait = chat.slotWait();
        if (slotWait == null || slotWait.isNegative()) {
            throw new IllegalStateException("app.chat.slot-wait는 0 이상이어야 합니다 (예: 5s).");
        }
        // 예산 검사는 AI를 쓰는 ollama 모드에서만 한다. off 모드는 질문을 바로 503으로 끝내 예산을 쓰지 않으므로 건너뛴다.
        // (OLLAMA_TIMEOUT 자체가 이상한 경우는 LlmConfig가 따로 막는다. 여기서는 쓸 수 있는 값일 때만 비교한다.)
        if ("ollama".equals(llm.mode()) && llm.ollama() != null) {
            Duration budget = llm.ollama().timeout();
            if (budget != null && budget.isPositive() && slotWait.compareTo(budget) >= 0) {
                throw new IllegalStateException("app.chat.slot-wait는 OLLAMA_TIMEOUT(질문 전체 시간)보다 짧아야 합니다.");
            }
        }
    }
}
