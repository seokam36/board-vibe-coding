package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 경쟁 조건을 "운에 맡기지 않고" 결정적으로 재현하는 도우미.
// 순서: (1) 별도 트랜잭션에서 DB 행을 잠근다 -> (2) 다른 스레드가 요청을 보낸다(잠금에서 대기)
//       -> (3) 정말 대기 중인지 확인 -> (4) 잠근 쪽이 삭제하고 커밋 -> (5) 요청의 결과를 돌려준다.
public final class LockRaceSupport {

	private LockRaceSupport() {
	}

	public static int 잠그고_요청하고_삭제커밋(PlatformTransactionManager txManager, JdbcTemplate jdbc,
			Runnable 잠금, Callable<Integer> 요청, Runnable 삭제) throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] holder = new Future[1];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				잠금.run();
				holder[0] = pool.submit(요청);
				try {
					락대기_기다리기(jdbc);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				// 진짜로 잠금에서 기다리는 중이어야 한다(이미 끝났다면 경쟁이 재현되지 않은 것)
				assertThat(holder[0].isDone()).as("요청이 락에서 대기 중이어야 한다").isFalse();
				삭제.run(); // 콜백이 끝나면 커밋된다 -> 기다리던 INSERT가 FK 위반으로 깨어난다
			});
			// 예상과 달리 요청이 안 깨어나면 영원히 멈추지 않게 10초만 기다린다
			return holder[0].get(10, TimeUnit.SECONDS);
		} finally {
			// 중간 단언이 실패해도 요청 스레드가 끝날 때까지 기다린다.
			// 안 기다리면 테스트가 끝난 뒤 @AfterEach의 정리와 요청이 동시에 돌아 엉뚱한 2차 오류가 원인을 가린다.
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	// 다른 커넥션이 락 때문에 기다리는 중(pg_stat_activity wait_event_type='Lock')이 될 때까지 최대 5초 폴링한다.
	public static void 락대기_기다리기(JdbcTemplate jdbc) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			Long n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
					+ "and wait_event_type = 'Lock' and pid <> pg_backend_pid()", Long.class);
			if (n != null && n > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("5초 안에 락 대기가 생기지 않았다");
	}
}
