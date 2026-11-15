package com.example.demo.chat.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.config.ChatProperties;

import lombok.extern.slf4j.Slf4j;

// 챗봇 "동시 질문 자리"(S17-f2). 서버 전체에서 AI를 쓰는 질문을 동시에 max-concurrent개(기본 2)까지만 받는다.
// 왜: Ollama는 내 PC 한 대라 질문이 몰리면 모두 느려져 줄줄이 504가 된다. 자리를 제한해 앞 질문부터 끝내 준다.
// 📦 java.util.concurrent.Semaphore: "번호표 N장". acquire로 한 장 집고 release로 돌려놓는다. 서버(EC2) 1대라 메모리 안에서 세면 충분하다(Redis 불필요).
@Slf4j
@Component
public class ChatSlots {

    // 응답 본문에는 나가지 않는다(server.error.include-message 기본값 never). ChatService.UNAVAILABLE_MESSAGE와 같은 고정 문구라 원인이 드러나지 않는다.
    static final String BUSY_MESSAGE = ChatService.UNAVAILABLE_MESSAGE;

    private final Semaphore permits;
    private final long waitMillis;

    public ChatSlots(ChatProperties props) {
        // 두 번째 인자 true = 공정 모드: 오래 기다린 질문이 먼저 자리를 받는다(새로 온 질문이 새치기해 먼저 온 질문이 계속 밀리는 것을 막는다).
        this.permits = new Semaphore(props.maxConcurrent(), true);
        this.waitMillis = props.slotWait().toMillis();
    }

    // 자리를 하나 잡는다. 없으면 slot-wait(기본 5초)까지 기다리고, 그래도 없으면 503. 잡았다면 반드시 release()로 돌려줘야 한다.
    public void acquire() {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 중단 신호를 되살려 둔다
            log.info("챗봇 자리 대기 중 중단됨 -> 503"); // 질문·회원 정보는 남기지 않는다
            throw busy();
        }
        if (!acquired) {
            log.info("챗봇 자리 없음: {}ms 기다림 -> 503", waitMillis);
            throw busy();
        }
    }

    // 자리를 돌려준다(acquire가 성공한 뒤 finally에서 딱 한 번).
    public void release() {
        permits.release();
    }

    // 남은 자리 수(테스트가 "끝나면 자리가 모두 돌아왔나"를 확인하는 용도. 다른 패키지의 테스트가 부르므로 public)
    public int availablePermits() {
        return permits.availablePermits();
    }

    // 자리를 기다리며 줄 서 있는 질문이 있는지(테스트가 "3번째 질문이 지금 대기 중"을 확인하는 용도)
    public boolean hasWaiting() {
        return permits.hasQueuedThreads();
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, BUSY_MESSAGE);
    }
}
