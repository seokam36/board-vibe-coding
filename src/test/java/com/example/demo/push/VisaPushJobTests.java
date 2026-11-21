package com.example.demo.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.push.repository.VisaPlanNotificationRepository;
import com.example.demo.push.service.NoopPushSender;
import com.example.demo.push.service.PushResult;
import com.example.demo.push.service.PushSender;
import com.example.demo.push.service.VisaPushJob;
import com.example.demo.push.service.VisaPushService;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.visa.repository.VisaPlanRepository;

// S24-c 완료 조건 검사: 비자 일정 푸시 알림 주기 작업(VisaPushJob.runOnce)과 보낸 기록 표(V17).
// 스케줄은 꺼져 있어서 runOnce(now)를 직접 부른다. 발송기는 테스트용 가짜(FakePushSender)로 바꿔 끼운다(구글을 안 부름).
// "오늘"은 서울 시계로 now에서 구한다: 기준 시각 2026-11-21 12:00(서울) = 2026-11-21T03:00:00Z.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, VisaPushJobTests.FakeConfig.class })
@ExtendWith(OutputCaptureExtension.class)
class VisaPushJobTests {

	static final Instant 정오 = Instant.parse("2026-11-21T03:00:00Z"); // 서울 12:00
	static final LocalDate 오늘 = LocalDate.of(2026, 11, 21);
	static final String 학교 = "연세대학교";

	// 테스트용 가짜 발송기: 토큰별 결과를 정해 두고(기본 SENT), 호출을 전부 기록한다
	static class FakePushSender implements PushSender {
		record Call(String token, String title, String body, boolean 트랜잭션열림) {
		}

		final List<Call> calls = new ArrayList<>();
		final Map<String, PushResult> results = new HashMap<>();
		final Set<String> 예외토큰 = new HashSet<>();
		volatile boolean enabled = true;
		volatile Consumer<String> 보내기전 = null; // 보내는 도중 DB를 건드리는 시험용

		@Override
		public boolean enabled() {
			return enabled;
		}

		@Override
		public PushResult send(String token, String title, String body) {
			calls.add(new Call(token, title, body, TransactionSynchronizationManager.isActualTransactionActive()));
			if (보내기전 != null) {
				보내기전.accept(token);
			}
			if (예외토큰.contains(token)) {
				throw new IllegalStateException("가짜 발송기 오류");
			}
			return results.getOrDefault(token, PushResult.SENT);
		}

		void reset() {
			calls.clear();
			results.clear();
			예외토큰.clear();
			enabled = true;
			보내기전 = null;
		}
	}

	@TestConfiguration
	static class FakeConfig {
		@Bean
		@Primary
		FakePushSender fakePushSender() {
			return new FakePushSender();
		}
	}

	@Autowired VisaPushJob job;
	@Autowired FakePushSender fake;
	@Autowired JdbcTemplate jdbc;
	@Autowired UserRepository userRepository;
	@Autowired VisaPlanRepository visaPlanRepository;
	@Autowired VisaPlanNotificationRepository notificationRepository;
	@Autowired VisaPushService visaPushService;
	@Autowired Clock clock;
	@Autowired TransactionTemplate transactionTemplate;

	int 순번 = 0;

	@BeforeEach
	void 준비() {
		정리();
		fake.reset();
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from visa_plan_notifications");
		jdbc.update("delete from device_tokens");
		jdbc.update("delete from visa_plans");
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	// 유학생 인증 회원 + 비자 일정(오늘에서 days일 뒤 만료) + 토큰 tokens
	private User 회원(int days, String... tokens) {
		User user = TestLogin.인증된_사용자(userRepository, jdbc, "pushu" + (++순번), 학교);
		일정(user, 오늘.plusDays(days));
		for (String token : tokens) {
			토큰(user, token);
		}
		return user;
	}

	private void 일정(User user, LocalDate expiry) {
		jdbc.update("insert into visa_plans (user_id, expiry_date, created_at, updated_at) values (?, ?, now(), now())",
				user.getId(), expiry);
	}

	private void 토큰(User user, String token) {
		jdbc.update("insert into device_tokens (user_id, token, created_at, updated_at) values (?, ?, now(), now())",
				user.getId(), token);
	}

	private void 기록(User user, LocalDate expiry, String stage) {
		jdbc.update("insert into visa_plan_notifications (user_id, expiry_date, stage, sent_at) values (?, ?, ?, now())",
				user.getId(), expiry, stage);
	}

	private int 기록수() {
		return jdbc.queryForObject("select count(*) from visa_plan_notifications", Integer.class);
	}

	private int 토큰수(User user) {
		return jdbc.queryForObject("select count(*) from device_tokens where user_id = ?", Integer.class, user.getId());
	}

	private List<String> 기록단계(User user) {
		return jdbc.queryForList("select stage from visa_plan_notifications where user_id = ? order by stage",
				String.class, user.getId());
	}

	// ---------- 보내기·기록 ----------

	@Test
	void 만료_120일_30일_7일_남은_인증_회원은_각각_한_번_보내고_기록_한_줄이_남는다() {
		User 준비 = 회원(120, "t-prepare");
		User 확인 = 회원(30, "t-check");
		User 임박 = 회원(7, "t-urgent");

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(3);
		assertThat(fake.calls).hasSize(3);
		assertThat(fake.calls).allSatisfy(c -> {
			assertThat(c.title()).isEqualTo("비자 일정 알림");
			assertThat(c.body()).isEqualTo("비자 일정을 확인할 때가 됐어요. 앱에서 확인해 주세요.");
		});
		assertThat(기록수()).isEqualTo(3);
		assertThat(기록단계(준비)).containsExactly("PREPARE");
		assertThat(기록단계(확인)).containsExactly("CHECK");
		assertThat(기록단계(임박)).containsExactly("URGENT");
		assertThat(jdbc.queryForObject("select expiry_date from visa_plan_notifications where user_id = ?",
				LocalDate.class, 확인.getId())).isEqualTo(오늘.plusDays(30));
	}

	@Test
	void 만료_당일_0일_남음도_URGENT로_보낸다() {
		User 당일 = 회원(0, "t-today");

		job.runOnce(정오);

		assertThat(기록단계(당일)).containsExactly("URGENT");
	}

	@Test
	void 다시_돌리면_보내지_않고_기록_수도_그대로다() {
		회원(120, "t1");
		회원(30, "t2");
		job.runOnce(정오);
		fake.calls.clear();

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isZero();
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isEqualTo(2);
	}

	@Test
	void 같은_회원이_다음_단계가_되면_한_번_더_보내고_기록이_두_줄이_된다() {
		User 회원 = 회원(30, "t1");
		기록(회원, 오늘.plusDays(30), "PREPARE"); // 120일 때 이미 보냈다

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(fake.calls).hasSize(1);
		assertThat(기록단계(회원)).containsExactly("CHECK", "PREPARE");
	}

	@Test
	void 만료일을_다른_날짜로_바꾸면_같은_단계라도_다시_한_번_보낸다() {
		User 회원 = 회원(30, "t1");
		job.runOnce(정오);
		fake.calls.clear();

		jdbc.update("update visa_plans set expiry_date = ? where user_id = ?", 오늘.plusDays(25), 회원.getId());
		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(fake.calls).hasSize(1);
		assertThat(기록수()).isEqualTo(2);
	}

	@Test
	void 지난_단계는_보내지_않고_지금_단계만_보낸다() {
		User 회원 = 회원(5, "t1"); // 120·30일 단계는 이미 지났다

		job.runOnce(정오);

		assertThat(fake.calls).hasSize(1);
		assertThat(기록단계(회원)).containsExactly("URGENT");
	}

	// ---------- 대상이 아닌 회원 ----------

	@Test
	void 대상이_아닌_회원은_보내지도_기록하지도_않는다() {
		회원(121, "t-far"); // 아직 멀다
		회원(-1, "t-expired"); // 이미 지났다
		TestLogin.인증된_사용자(userRepository, jdbc, "pushnoplan", 학교); // 일정 없음
		토큰(userRepository.findByUsername("pushnoplan").orElseThrow(), "t-noplan");
		회원(30); // 토큰 없음
		User 미인증 = TestLogin.사용자(userRepository, "pushunverified");
		일정(미인증, 오늘.plusDays(30));
		토큰(미인증, "t-unverified");
		User 취소 = 회원(30, "t-revoked");
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				취소.getId()); // 인증 취소

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isZero();
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isZero();
	}

	// ---------- 토큰 여러 개·실패 ----------

	@Test
	void 토큰이_두_개인_회원은_두_번_보내고_기록은_한_줄이다() {
		User 회원 = 회원(30, "t1", "t2");

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(fake.calls).hasSize(2);
		assertThat(기록단계(회원)).containsExactly("CHECK");
	}

	@Test
	void 못_쓰는_토큰_행은_지우고_같은_회원의_다른_토큰이_성공하면_기록이_남는다() {
		User 회원 = 회원(30, "t-dead", "t-ok");
		fake.results.put("t-dead", PushResult.TOKEN_INVALID);

		job.runOnce(정오);

		assertThat(jdbc.queryForList("select token from device_tokens where user_id = ?", String.class, 회원.getId()))
				.containsExactly("t-ok");
		assertThat(기록단계(회원)).containsExactly("CHECK");
	}

	@Test
	void 못_쓰는_토큰만_있으면_토큰은_지우고_기록은_없다() {
		User 회원 = 회원(30, "t-dead");
		fake.results.put("t-dead", PushResult.TOKEN_INVALID);

		job.runOnce(정오);

		assertThat(토큰수(회원)).isZero();
		assertThat(기록수()).isZero();
	}

	@Test
	void 보내는_동안_토큰_주인이_바뀌었으면_못_쓰는_토큰이라도_지우지_않는다() {
		User 회원 = 회원(30, "t-moved");
		User 다른회원 = TestLogin.사용자(userRepository, "pushother");
		fake.results.put("t-moved", PushResult.TOKEN_INVALID);
		// 구글에 보내는 사이에 같은 토큰을 다른 회원이 등록해 주인이 바뀐 상황
		fake.보내기전 = t -> jdbc.update("update device_tokens set user_id = ? where token = ?", 다른회원.getId(), t);

		job.runOnce(정오);

		assertThat(토큰수(다른회원)).isEqualTo(1);
		assertThat(기록수()).isZero();
	}

	@Test
	void 모든_토큰이_FAILED면_기록도_삭제도_없고_다음_회차에_다시_시도한다() {
		User 회원 = 회원(30, "t1", "t2");
		fake.results.put("t1", PushResult.FAILED);
		fake.results.put("t2", PushResult.FAILED);

		int 첫째 = job.runOnce(정오);

		assertThat(첫째).isZero();
		assertThat(기록수()).isZero();
		assertThat(토큰수(회원)).isEqualTo(2);
		assertThat(fake.calls).hasSize(2);

		fake.results.clear(); // 구글이 살아남
		int 둘째 = job.runOnce(정오);

		assertThat(둘째).isEqualTo(1);
		assertThat(fake.calls).hasSize(4);
		assertThat(기록단계(회원)).containsExactly("CHECK");
	}

	@Test
	void 한_회원_처리_중_예외가_나도_다음_회원은_처리된다() {
		User 먼저 = 회원(30, "t-boom");
		User 다음 = 회원(30, "t-fine");
		fake.예외토큰.add("t-boom");

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(기록단계(먼저)).isEmpty();
		assertThat(기록단계(다음)).containsExactly("CHECK");
	}

	@Test
	void 연속_FAILED_회원이_기준_수에_닿으면_그_회차의_남은_회원은_보내지_않는다(CapturedOutput output) {
		int 기준 = 5;
		for (int i = 1; i <= 기준 + 2; i++) {
			회원(30, "t-fail-" + i);
			fake.results.put("t-fail-" + i, PushResult.FAILED);
		}

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isZero();
		assertThat(fake.calls).hasSize(기준); // 5명째에서 멈춤, 나머지 2명은 부르지 않음
		assertThat(output.getAll()).contains("연속 실패로 이번 회차 중단");
		assertThat(output.getAll()).doesNotContain("t-fail-");
	}

	@Test
	void 중간에_성공이_나오면_연속_실패_수가_처음부터_다시_센다() {
		for (int i = 1; i <= 4; i++) {
			회원(30, "t-a" + i);
			fake.results.put("t-a" + i, PushResult.FAILED);
		}
		회원(30, "t-ok"); // 성공 -> 카운트 초기화
		for (int i = 1; i <= 4; i++) {
			회원(30, "t-b" + i);
			fake.results.put("t-b" + i, PushResult.FAILED);
		}

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(fake.calls).hasSize(9); // 중단 없이 전원 시도
	}

	@Test
	void 못_쓰는_토큰_답이_나와도_연속_실패_수가_초기화된다() {
		for (int i = 1; i <= 4; i++) {
			회원(30, "t-a" + i);
			fake.results.put("t-a" + i, PushResult.FAILED);
		}
		회원(30, "t-dead");
		fake.results.put("t-dead", PushResult.TOKEN_INVALID);
		for (int i = 1; i <= 4; i++) {
			회원(30, "t-b" + i);
			fake.results.put("t-b" + i, PushResult.FAILED);
		}

		job.runOnce(정오);

		assertThat(fake.calls).hasSize(9);
	}

	// ---------- S24-c 리뷰 반영: 거절(REJECTED)·잠금·재확인·기록 실패 ----------

	@Test
	void REJECTED_회원이_기준_수보다_많이_앞에_있어도_뒤_회원은_보낸다() {
		for (int i = 1; i <= 7; i++) { // 기준(5)보다 많다
			회원(30, "t-rej-" + i);
			fake.results.put("t-rej-" + i, PushResult.REJECTED);
		}
		User 뒤 = 회원(30, "t-ok");

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(fake.calls).hasSize(8); // 중단 없이 전원 시도
		assertThat(기록단계(뒤)).containsExactly("CHECK");
		assertThat(기록수()).isEqualTo(1);
	}

	@Test
	void REJECTED만_나온_회원은_기록도_토큰_삭제도_없고_다음_회차에_다시_보낸다(CapturedOutput output) {
		User 회원 = 회원(30, "t1", "t2");
		fake.results.put("t1", PushResult.REJECTED);
		fake.results.put("t2", PushResult.FAILED); // 거절 + 무응답 섞임 -> 구글이 답은 했으니 거절 쪽

		assertThat(job.runOnce(정오)).isZero();

		assertThat(기록수()).isZero();
		assertThat(토큰수(회원)).isEqualTo(2);
		assertThat(output.getAll()).contains("거절 1명");

		fake.results.clear();
		assertThat(job.runOnce(정오)).isEqualTo(1);
		assertThat(기록단계(회원)).containsExactly("CHECK");
	}

	@Test
	void 거절이_끼면_연속_FAILED_수가_초기화된다() {
		for (int i = 1; i <= 4; i++) {
			회원(30, "t-a" + i);
			fake.results.put("t-a" + i, PushResult.FAILED);
		}
		회원(30, "t-rej");
		fake.results.put("t-rej", PushResult.REJECTED);
		for (int i = 1; i <= 4; i++) {
			회원(30, "t-b" + i);
			fake.results.put("t-b" + i, PushResult.FAILED);
		}

		job.runOnce(정오);

		assertThat(fake.calls).hasSize(9); // 중단 없이 전원 시도
	}

	@Test
	void 토큰_셋_중_일부는_FAILED_나머지는_SENT면_기록은_한_줄이고_토큰은_그대로다() {
		User 회원 = 회원(30, "t1", "t2", "t3");
		fake.results.put("t1", PushResult.FAILED);
		fake.results.put("t3", PushResult.FAILED);

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(fake.calls).hasSize(3);
		assertThat(기록단계(회원)).containsExactly("CHECK");
		assertThat(토큰수(회원)).isEqualTo(3);
	}

	@Test
	void 보내는_도중_탈퇴한_회원은_기록_없이_조용히_넘어가고_오류로_세지_않는다(CapturedOutput output) {
		User 탈퇴 = 회원(30, "t-gone");
		User 다음 = 회원(30, "t-next");
		fake.보내기전 = t -> {
			if (t.equals("t-gone")) {
				jdbc.update("delete from users where id = ?", 탈퇴.getId()); // 토큰·일정은 CASCADE로 같이 사라짐
			}
		};

		int 보냄 = job.runOnce(정오);

		assertThat(보냄).isEqualTo(1);
		assertThat(기록수()).isEqualTo(1);
		assertThat(기록단계(다음)).containsExactly("CHECK");
		assertThat(output.getAll()).contains("건너뜀 1명").contains("오류 0명");
		assertThat(output.getAll()).doesNotContain("처리 오류 1건");
	}

	@Test
	void 발송_전_재확인에서_인증이_취소됐으면_보내지_않는다() {
		User 회원 = 회원(30, "t1");
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				회원.getId());

		var 결과 = visaPushService.notifyMember(회원.getId(), 오늘.plusDays(30), com.example.demo.visa.domain.VisaPlanStage.CHECK);

		assertThat(결과).isEqualTo(VisaPushService.MemberResult.SKIPPED);
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isZero();
	}

	@Test
	void 발송_전_재확인에서_만료일이_바뀌었으면_보내지_않는다() {
		User 회원 = 회원(30, "t1");
		LocalDate 후보때만료일 = 오늘.plusDays(30);
		jdbc.update("update visa_plans set expiry_date = ? where user_id = ?", 오늘.plusDays(200), 회원.getId());

		var 결과 = visaPushService.notifyMember(회원.getId(), 후보때만료일, com.example.demo.visa.domain.VisaPlanStage.CHECK);

		assertThat(결과).isEqualTo(VisaPushService.MemberResult.SKIPPED);
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isZero();
	}

	// 기록 저장(INSERT)이 실패하는 상황을 DB 트리거로 만든다(저장소 예외와 같은 효과)
	@Test
	void 보냈는데_기록_저장이_실패하면_기록이_없고_다음_회차에_다시_보낸다() {
		User 회원 = 회원(30, "t1");
		jdbc.execute("create function fail_notif_insert() returns trigger as $$ begin raise exception 'insert blocked'; end; $$ language plpgsql");
		jdbc.execute("create trigger trg_fail_notif before insert on visa_plan_notifications "
				+ "for each row execute function fail_notif_insert()");
		try {
			assertThat(job.runOnce(정오)).isZero();
			assertThat(기록수()).isZero();
			assertThat(토큰수(회원)).isEqualTo(1);
			assertThat(fake.calls).hasSize(1);
		} finally {
			jdbc.execute("drop trigger trg_fail_notif on visa_plan_notifications");
			jdbc.execute("drop function fail_notif_insert()");
		}

		assertThat(job.runOnce(정오)).isEqualTo(1); // 다음 회차에 다시 보내 기록이 남는다
		assertThat(fake.calls).hasSize(2);
		assertThat(기록단계(회원)).containsExactly("CHECK");
	}

	// ---------- 시간 창·꺼짐 ----------

	@Test
	void 서울_03시_08시59분_21시_정각은_보내지_않고_09시_정각과_20시59분은_보낸다() {
		회원(30, "t1");

		assertThat(job.runOnce(Instant.parse("2026-11-20T18:00:00Z"))).isZero(); // 서울 03:00
		assertThat(job.runOnce(Instant.parse("2026-11-20T23:59:00Z"))).isZero(); // 서울 08:59
		assertThat(job.runOnce(Instant.parse("2026-11-21T12:00:00Z"))).isZero(); // 서울 21:00 (끝은 미포함)
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isZero();

		assertThat(job.runOnce(Instant.parse("2026-11-21T00:00:00Z"))).isEqualTo(1); // 서울 09:00 (시작은 포함)
		assertThat(기록수()).isEqualTo(1);
	}

	@Test
	void 서울_20시59분은_창_안이다() {
		회원(30, "t1");

		assertThat(job.runOnce(Instant.parse("2026-11-21T11:59:00Z"))).isEqualTo(1); // 서울 20:59
	}

	@Test
	void 발송기가_꺼져_있으면_보내지도_기록하지도_않는다() {
		회원(30, "t1");
		fake.enabled = false;

		assertThat(job.runOnce(정오)).isZero();
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isZero();
	}

	@Test
	void 진짜_꺼진_발송기_Noop이어도_아무것도_하지_않는다() {
		회원(30, "t1");
		VisaPushJob noopJob = new VisaPushJob(new NoopPushSender(), visaPlanRepository, notificationRepository,
				visaPushService, clock, 9, 21);

		assertThat(noopJob.runOnce(정오)).isZero();
		assertThat(fake.calls).isEmpty();
		assertThat(기록수()).isZero();
	}

	// ---------- 트랜잭션·제약·로그 ----------

	@Test
	void 구글로_보내는_동안_DB_트랜잭션이_열려_있지_않다() {
		회원(30, "t1", "t2");
		회원(7, "t3");

		job.runOnce(정오);

		assertThat(fake.calls).hasSize(3);
		assertThat(fake.calls).allSatisfy(c -> assertThat(c.트랜잭션열림()).isFalse());
	}

	@Test
	void V17_제약_이름이_실제_DB에_있다() {
		List<String> 제약 = jdbc.queryForList(
				"select conname from pg_constraint where conrelid = 'visa_plan_notifications'::regclass", String.class);
		assertThat(제약).contains("visa_plan_notifications_pkey", "fk_visa_plan_notifications_user",
				"ck_visa_plan_notifications_stage", "uk_visa_plan_notifications_user_expiry_stage");

		// FK가 ON DELETE CASCADE인지(탈퇴 때 DB가 같이 지움)
		String 삭제규칙 = jdbc.queryForObject(
				"select confdeltype::text from pg_constraint where conname = 'fk_visa_plan_notifications_user'", String.class);
		assertThat(삭제규칙).isEqualTo("c");
	}

	@Test
	void 단계_CHECK_제약은_EXPIRED와_엉뚱한_값을_거절한다() {
		User 회원 = 회원(30);

		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
				() -> 기록(회원, 오늘.plusDays(30), "EXPIRED"));
	}

	@Test
	void 같은_기록을_또_넣으면_ON_CONFLICT로_조용히_넘어가고_한_줄만_남는다() {
		User 회원 = 회원(30);
		기록(회원, 오늘.plusDays(30), "CHECK");

		// 저장소는 트랜잭션 안에서만 부를 수 있다(MANDATORY)
		int 넣은줄 = transactionTemplate.execute(
				s -> notificationRepository.insertIfAbsent(회원.getId(), 오늘.plusDays(30), "CHECK"));

		assertThat(넣은줄).isZero(); // 충돌이라 0줄, 예외 없음
		assertThat(기록수()).isEqualTo(1);
	}

	// ---------- 추가: 경계값·스케줄 설정·서울 날짜 ----------

	@Test
	void 경계_날짜는_화면_띠와_같은_단계로_보낸다_120일_31일_30일_8일_7일_0일은_보내고_121일은_안_보낸다() {
		// 남은 일수 -> 기대 단계 (VisaPlanStage와 같아야 한다)
		Map<Integer, String> 기대 = new java.util.LinkedHashMap<>();
		기대.put(120, "PREPARE");
		기대.put(31, "PREPARE");
		기대.put(30, "CHECK");
		기대.put(8, "CHECK");
		기대.put(7, "URGENT");
		기대.put(0, "URGENT");
		Map<Integer, User> 회원들 = new java.util.LinkedHashMap<>();
		for (int days : 기대.keySet()) {
			회원들.put(days, 회원(days, "t-b" + days));
		}
		User 먼 = 회원(121, "t-b121");

		job.runOnce(정오);

		for (var e : 기대.entrySet()) {
			assertThat(기록단계(회원들.get(e.getKey()))).as("남은 %d일", e.getKey()).containsExactly(e.getValue());
			// 화면 띠 클래스와도 같은지
			assertThat(com.example.demo.visa.domain.VisaPlanStage.of(e.getKey()).name()).isEqualTo(e.getValue());
		}
		assertThat(기록단계(먼)).isEmpty();
		assertThat(fake.calls).hasSize(기대.size());
	}

	@Test
	void 스케줄_애너테이션은_fixedDelay와_initialDelay이고_기본값은_1시간_5분이다() throws Exception {
		var m = VisaPushJob.class.getDeclaredMethod("scheduledRun");
		var sch = m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
		assertThat(sch).isNotNull();
		assertThat(sch.cron()).isEmpty();
		assertThat(sch.fixedRate()).isEqualTo(-1L);
		assertThat(sch.fixedDelayString()).contains("app.push.visa.interval");
		assertThat(sch.initialDelayString()).contains("app.push.visa.initial-delay");
		var env = context.getEnvironment();
		assertThat(java.time.Duration.parse(env.resolvePlaceholders(sch.fixedDelayString()))).isEqualTo(java.time.Duration.ofHours(1));
		assertThat(java.time.Duration.parse(env.resolvePlaceholders(sch.initialDelayString()))).isEqualTo(java.time.Duration.ofMinutes(5));
		assertThat(env.getProperty("app.push.visa.window-start-hour", Integer.class)).isEqualTo(9);
		assertThat(env.getProperty("app.push.visa.window-end-hour", Integer.class)).isEqualTo(21);
	}

	@Test
	void 테스트_설정에서는_스케줄링이_꺼져_있어_저절로_돌지_않는다() {
		assertThat(context.getEnvironment().getProperty("app.scheduling.enabled")).isEqualTo("false");
		assertThat(context.getBeanNamesForType(com.example.demo.config.SchedulingConfig.class)).isEmpty();
		assertThat(context.getBeanNamesForType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class))
				.isEmpty();
	}

	@Test
	void 잘못된_시간_창_설정이면_생성에_실패한다() {
		int[][] 나쁜값 = { { 21, 9 }, { 9, 9 }, { 9, 25 }, { -1, 21 }, { 24, 24 } };
		for (int[] w : 나쁜값) {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> new VisaPushJob(fake, visaPlanRepository,
					notificationRepository, visaPushService, clock, w[0], w[1]))
					.as("%d~%d", w[0], w[1]).isInstanceOf(IllegalArgumentException.class);
		}
		new VisaPushJob(fake, visaPlanRepository, notificationRepository, visaPushService, clock, 0, 24); // 정상
	}

	@Test
	void 스케줄_진입점은_예외가_나도_밖으로_던지지_않는다() {
		var 고장난저장소 = org.mockito.Mockito.mock(VisaPlanRepository.class);
		org.mockito.Mockito.when(고장난저장소.findPushCandidates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenThrow(new IllegalStateException("db down"));
		// 시계가 시간 창 안이어야 저장소까지 간다: 서울 정오 고정 시계
		Clock 정오시계 = Clock.fixed(정오, java.time.ZoneId.of("Asia/Seoul"));
		VisaPushJob j = new VisaPushJob(fake, 고장난저장소, notificationRepository, visaPushService, 정오시계, 9, 21);
		org.assertj.core.api.Assertions
				.assertThatCode(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(j, "scheduledRun"))
				.doesNotThrowAnyException();
	}

	@Test
	void 오늘_날짜는_서울_기준이다_UTC로는_전날인_서울_00시30분에도_서울_날짜로_계산한다() {
		// 창을 0~24로 열어 서울 00:30(= UTC 전날 15:30)에 돌린다. 서울 오늘 = 11-21, UTC 날짜는 11-20.
		VisaPushJob 종일 = new VisaPushJob(fake, visaPlanRepository, notificationRepository, visaPushService, clock, 0, 24);
		User 경계 = 회원(120, "t-edge120"); // 서울 기준 정확히 120일: UTC 날짜로 계산하면 121일이라 빠진다
		User 당일 = 회원(0, "t-edge0"); // 서울 기준 만료 당일: UTC 날짜로 계산하면 -?일 아닌 +1일이라 단계가 같아 구분 안 됨(참고용)
		User 지난 = 회원(-1, "t-edge-1"); // 서울 기준 어제 만료: UTC 날짜 기준이면 0일(URGENT)로 잘못 보낸다

		int 보냄 = 종일.runOnce(Instant.parse("2026-11-20T15:30:00Z"));

		assertThat(보냄).isEqualTo(2);
		assertThat(기록단계(경계)).containsExactly("PREPARE");
		assertThat(기록단계(당일)).containsExactly("URGENT");
		assertThat(기록단계(지난)).isEmpty();
	}

	@Test
	void 서울_09시_정각_UTC_00시에도_서울_날짜로_계산한다() {
		User 경계 = 회원(120, "t-e120");
		User 먼 = 회원(121, "t-e121");

		job.runOnce(Instant.parse("2026-11-21T00:00:00Z")); // 서울 09:00

		assertThat(기록단계(경계)).containsExactly("PREPARE");
		assertThat(기록단계(먼)).isEmpty();
	}

	@Autowired org.springframework.context.ApplicationContext context;

	@Test
	void 로그에는_토큰_값과_만료일이_없다(CapturedOutput output) {
		회원(30, "secret-token-value-xyz");

		job.runOnce(정오);

		assertThat(output.getAll()).contains("비자 일정 푸시 알림: 대상 1명, 보냄 1명");
		assertThat(output.getAll()).doesNotContain("secret-token-value-xyz");
		assertThat(output.getAll()).doesNotContain(오늘.plusDays(30).toString());
	}
}
