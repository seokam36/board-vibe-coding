package com.example.demo.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// S13-g6-4: 청소부가 "처음 성공한 scheduledRun"에서만 확인용 INFO 한 줄을 남기는지 검사한다.
// 스프링·DB 없이 가짜(Mockito)로 만든 청소부를 쓰는 빠른 단위 테스트. 로그는 Logback ListAppender로 잡는다.
class PhotoCleanupJobFirstRunLogTests {

    private static final String 첫_실행_0개 = "사진 청소부 첫 실행: 0개 처리 (이후엔 처리한 게 있을 때만 로그)";

    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final PhotoCleanupJob job = new PhotoCleanupJob(mock(NamedParameterJdbcTemplate.class),
            mock(PhotoDeleteQueue.class), tx, Clock.systemUTC(), Duration.ofMinutes(10));

    private final Logger logger = (Logger) LoggerFactory.getLogger(PhotoCleanupJob.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level 원래_레벨; // null일 수 있다(부모 설정을 따르는 중)

    @BeforeEach
    void 로그_잡기_시작() {
        // 다른 설정이 이 로거 레벨을 올려 두어도 INFO가 잡히도록 고정한다
        원래_레벨 = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void 로그_잡기_끝() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(원래_레벨);
    }

    private List<String> 메시지들() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @SuppressWarnings("unchecked")
    private void 꺼낼_줄이_없다() {
        // runOnce의 "꺼내기" 트랜잭션이 빈 목록을 돌려줘서 0개 처리
        when(tx.execute(any(TransactionCallback.class))).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void 꺼내다_예외가_난다() {
        when(tx.execute(any(TransactionCallback.class))).thenThrow(new IllegalStateException("DB 오류"));
    }

    @Test
    void 첫_실행에는_0개여도_첫_실행_INFO_한_줄이_찍힌다() {
        꺼낼_줄이_없다();

        job.scheduledRun();

        assertThat(메시지들()).containsExactly(첫_실행_0개);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void 두_번째_실행부터는_처리한_게_없으면_아무것도_안_찍는다() {
        꺼낼_줄이_없다();

        job.scheduledRun();
        appender.list.clear();
        job.scheduledRun();
        job.scheduledRun();

        assertThat(메시지들()).isEmpty();
    }

    @Test
    void 첫_실행이_예외면_성공할_때까지_첫_실행_경고가_계속_찍히고_예외가_실린다() {
        꺼내다_예외가_난다();

        job.scheduledRun();
        job.scheduledRun();

        assertThat(메시지들()).containsExactly("사진 청소부 첫 실행에 실패했습니다(다음 주기에 다시 합니다)",
                "사진 청소부 첫 실행에 실패했습니다(다음 주기에 다시 합니다)");
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void 첫_실행이_예외여도_다음에_처음_성공하면_첫_실행_INFO가_찍히고_그_다음은_조용하다() {
        PhotoCleanupJob spy = spy(job);
        // runOnce 결과: 첫 호출 예외, 이후 0개, 0개
        doThrow(new IllegalStateException("DB 오류")).doReturn(0, 0).when(spy).runOnce();

        spy.scheduledRun(); // 실패
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        appender.list.clear();

        spy.scheduledRun(); // 처음 성공
        assertThat(메시지들()).containsExactly(첫_실행_0개);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        appender.list.clear();

        spy.scheduledRun(); // 0개 성공
        assertThat(메시지들()).isEmpty();
    }

    @Test
    void 첫_실행에서_3개를_처리하면_첫_실행_한_줄만_찍히고_n개_처리_로그는_안_찍힌다() {
        PhotoCleanupJob spy = spy(job);
        doReturn(3).when(spy).runOnce();

        spy.scheduledRun();

        assertThat(메시지들()).containsExactly("사진 청소부 첫 실행: 3개 처리 (이후엔 처리한 게 있을 때만 로그)");
    }

    @Test
    void 두_번째_실행에서_처리한_게_있으면_기존_n개_처리_로그가_그대로_찍힌다() {
        // 진짜 DB 없이 runOnce 결과만 정하려고 스파이를 쓴다: 첫 번째 0개, 두 번째 3개
        PhotoCleanupJob spy = spy(job);
        doReturn(0, 3).when(spy).runOnce();

        spy.scheduledRun();
        appender.list.clear();
        spy.scheduledRun();

        assertThat(메시지들()).containsExactly("사진 청소부: 3개 처리");
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }
}
