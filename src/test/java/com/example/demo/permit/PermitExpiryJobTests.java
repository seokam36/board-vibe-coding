package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.permit.service.PermitExpiryJob;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.repository.StoredPhoto;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.user.domain.User;

import tools.jackson.databind.JsonNode;

// S20-c 허가서 대기 신청 14일 자동 만료 테스트. 스케줄은 꺼져 있어서 runOnce(now)를 직접 부른다.
// created_at은 JDBC로 과거로 바꿔서 "오래된 신청"을 만든다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PermitExpiryJobTests extends AdminPermitTestBase {

	@Autowired PermitExpiryJob job;
	@Autowired PhotoDeleteQueue photoDeleteQueue;

	static final Duration TTL = Duration.ofDays(14);

	Instant 지금;
	int 원래배치;
	PhotoStorage 진짜창고;
	삭제실패창고 창고;

	// 삭제만 일부러 실패시키는 가짜 창고
	static class 삭제실패창고 implements PhotoStorage {
		final PhotoStorage 진짜;
		volatile boolean 지우기실패;

		삭제실패창고(PhotoStorage 진짜) {
			this.진짜 = 진짜;
		}

		@Override
		public void put(String key, byte[] bytes, String contentType) {
			진짜.put(key, bytes, contentType);
		}

		@Override
		public Optional<byte[]> get(String key) {
			return 진짜.get(key);
		}

		@Override
		public Optional<StoredPhoto> open(String key) {
			return 진짜.open(key);
		}

		@Override
		public void delete(String key) {
			if (지우기실패) {
				throw new PhotoStorageException("삭제 실패 " + key + " SECRETPATH-C:/disk/permits", new RuntimeException("원인 " + key));
			}
			진짜.delete(key);
		}
	}

	@BeforeEach
	void 만료준비() {
		지금 = Instant.now().truncatedTo(ChronoUnit.MICROS);
		원래배치 = (int) ReflectionTestUtils.getField(job, "batchSize");
		진짜창고 = (PhotoStorage) ReflectionTestUtils.getField(photoDeleteQueue, "photoStorage");
		창고 = new 삭제실패창고(진짜창고);
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 창고);
	}

	@AfterEach
	void 만료정리() {
		ReflectionTestUtils.setField(job, "batchSize", 원래배치);
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 진짜창고);
		jdbc.execute("drop trigger if exists t_fail_expire on permit_requests");
		jdbc.execute("drop function if exists fail_expire()");
	}

	// ---------- 도우미 ----------

	private void 생성시각(long id, Instant at) {
		jdbc.update("update permit_requests set created_at = ? where id = ?", Timestamp.from(at), id);
	}

	private long 오래된신청(User u, Duration 나이) throws Exception {
		long id = 신청(u);
		생성시각(id, 지금.minus(나이));
		return id;
	}

	private String 상태(long id) {
		return (String) 신청행(id).get("status");
	}

	// ---------- 1. 만료 처리 ----------

	@Test
	void 오래된_대기_신청은_만료되어_반려EXPIRED_시각기록_결정자없음_사진삭제_대기표비움() throws Exception {
		User u = 회원("pa");
		long id = 오래된신청(u, Duration.ofDays(15));
		assertThat(파일수()).isEqualTo(1);

		int n = job.runOnce(지금);

		assertThat(n).isEqualTo(1);
		var row = 신청행(id);
		assertThat(row.get("status")).isEqualTo("REJECTED");
		assertThat(row.get("reject_reason")).isEqualTo("EXPIRED");
		assertThat(((java.sql.Timestamp) row.get("decided_at")).toInstant()).isEqualTo(지금);
		assertThat(row.get("decided_by")).isNull();
		assertThat(row.get("photo_key")).isNull();
		assertThat(row.get("content_type")).isNull();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	// ---------- 2. 경계 ----------

	@Test
	void 경계_created_at이_cutoff와_같으면_만료되지_않고_1초_앞이면_만료된다() throws Exception {
		User a = 회원("pa");
		User b = 회원("pb");
		User c = 회원("pc");
		long 같음 = 오래된신청(a, TTL); // created_at == cutoff
		long 앞 = 오래된신청(b, TTL.plusSeconds(1)); // cutoff보다 1초 먼저
		long 뒤 = 오래된신청(c, TTL.minusSeconds(1)); // cutoff보다 1초 나중

		int n = job.runOnce(지금);

		assertThat(n).isEqualTo(1);
		assertThat(상태(앞)).isEqualTo("REJECTED");
		assertThat(상태(같음)).as("created_at < cutoff 이므로 같으면 아직 대기").isEqualTo("PENDING");
		assertThat(상태(뒤)).isEqualTo("PENDING");
		assertThat(파일수()).isEqualTo(2);
		assertThat(신청행(같음).get("photo_key")).isNotNull();
	}

	@Test
	void 경계_cutoff보다_1마이크로초_앞이면_만료된다() throws Exception {
		long id = 신청(회원("pa"));
		생성시각(id, 지금.minus(TTL).minus(1, ChronoUnit.MICROS));
		assertThat(job.runOnce(지금)).isEqualTo(1);
		assertThat(상태(id)).isEqualTo("REJECTED");
	}

	@Test
	void 열네일_안_된_신청은_그대로_대기이고_사진이_남는다() throws Exception {
		long id = 오래된신청(회원("pa"), Duration.ofDays(3));
		assertThat(job.runOnce(지금)).isZero();
		assertThat(상태(id)).isEqualTo("PENDING");
		assertThat(신청행(id).get("photo_key")).isNotNull();
		assertThat(파일수()).isEqualTo(1);
	}

	// ---------- 3. 회원 화면 ----------

	@Test
	void 만료된_회원은_내상태가_REJECTED_EXPIRED이고_다시_신청하면_201이다() throws Exception {
		User u = 회원("pa");
		오래된신청(u, Duration.ofDays(20));
		job.runOnce(지금);

		JsonNode me = json(내상태(u));
		assertThat(me.get("status").asString()).isEqualTo("REJECTED");
		assertThat(me.get("rejectReason").asString()).isEqualTo("EXPIRED");

		var r = mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(내상태(u)).get("status").asString()).isEqualTo("PENDING");
	}

	// ---------- 4. 관리자 목록 ----------

	@Test
	void 만료된_신청이_관리자_REJECTED_목록에_나오고_응답이_깨지지_않는다() throws Exception {
		User u = 회원("pa");
		long id = 오래된신청(u, Duration.ofDays(20));
		job.runOnce(지금);

		var r = 목록(관리자쿠키, "?status=REJECTED");
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode arr = json(r);
		assertThat(arr.size()).isEqualTo(1);
		JsonNode x = arr.get(0);
		assertThat(x.get("id").asLong()).isEqualTo(id);
		assertThat(x.get("status").asString()).isEqualTo("REJECTED");
		assertThat(x.get("rejectReason").asString()).isEqualTo("EXPIRED");
		assertThat(x.get("decidedAt").isNull()).isFalse();
		assertThat(x.get("hasPhoto").asBoolean()).isFalse();
		assertThat(json(목록(관리자쿠키, "?status=PENDING")).size()).isZero();
	}

	// ---------- 5. 이미 처리된 건은 건드리지 않음 ----------

	@Test
	void 이미_승인_또는_반려된_오래된_신청은_건드리지_않는다() throws Exception {
		User a = 회원("pa");
		User b = 회원("pb");
		long ida = 신청(a);
		long idb = 신청(b);
		assertThat(승인(ida, 연세id, "OLD-1").getStatus()).isEqualTo(200);
		assertThat(반려(idb, "BLURRY").getStatus()).isEqualTo(200);
		생성시각(ida, 지금.minus(Duration.ofDays(30)));
		생성시각(idb, 지금.minus(Duration.ofDays(30)));
		Object 결정a = 신청행(ida).get("decided_at");
		Object 결정b = 신청행(idb).get("decided_at");

		assertThat(job.runOnce(지금)).isZero();

		assertThat(상태(ida)).isEqualTo("APPROVED");
		assertThat(신청행(ida).get("decided_at")).isEqualTo(결정a);
		assertThat(상태(idb)).isEqualTo("REJECTED");
		assertThat(신청행(idb).get("reject_reason")).isEqualTo("BLURRY");
		assertThat(신청행(idb).get("decided_at")).isEqualTo(결정b);
		assertThat(신청행(idb).get("decided_by")).isNotNull();
	}

	// ---------- 6. 배치 상한 ----------

	@Test
	void 배치_크기가_작아도_한_번_실행에서_묶음을_이어_읽어_전부_처리한다() throws Exception {
		long id1 = 오래된신청(회원("p1"), Duration.ofDays(40));
		long id2 = 오래된신청(회원("p2"), Duration.ofDays(30));
		long id3 = 오래된신청(회원("p3"), Duration.ofDays(20));
		ReflectionTestUtils.setField(job, "batchSize", 2);

		assertThat(job.runOnce(지금)).isEqualTo(3);
		assertThat(상태(id1)).isEqualTo("REJECTED");
		assertThat(상태(id2)).isEqualTo("REJECTED");
		assertThat(상태(id3)).isEqualTo("REJECTED");
		assertThat(job.runOnce(지금)).isZero();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	// 해당 회원들의 신청을 REJECTED로 바꾸려 하면 DB가 예외를 내는 트리거(실패 건 흉내)
	private void 실패트리거(User... users) {
		String ids = java.util.Arrays.stream(users).map(x -> String.valueOf(x.getId()))
				.collect(java.util.stream.Collectors.joining(","));
		jdbc.execute("create function fail_expire() returns trigger language plpgsql as $$ begin "
				+ "if new.user_id in (" + ids + ") and new.status = 'REJECTED' then raise exception 'boom-secret'; end if; "
				+ "return new; end $$");
		jdbc.execute("create trigger t_fail_expire before update on permit_requests for each row execute function fail_expire()");
	}

	@Test
	void 맨_앞_건들이_실패해도_한_번_실행에서_나머지가_모두_처리되고_무한반복없이_끝난다() throws Exception {
		User u1 = 회원("p1");
		User u2 = 회원("p2");
		long id1 = 오래된신청(u1, Duration.ofDays(50));
		long id2 = 오래된신청(u2, Duration.ofDays(45));
		long[] rest = new long[3];
		for (int i = 0; i < 3; i++) {
			rest[i] = 오래된신청(회원("q" + i), Duration.ofDays(30 - i));
		}
		ReflectionTestUtils.setField(job, "batchSize", 2);
		실패트리거(u1, u2);

		assertThat(job.runOnce(지금)).isEqualTo(3);

		assertThat(상태(id1)).isEqualTo("PENDING");
		assertThat(상태(id2)).isEqualTo("PENDING");
		assertThat(신청행(id1).get("photo_key")).isNotNull();
		assertThat(신청행(id2).get("photo_key")).isNotNull();
		for (long id : rest) {
			assertThat(상태(id)).isEqualTo("REJECTED");
		}
		assertThat(파일수()).isEqualTo(2);
		assertThat(대기표수()).isZero();
	}

	// ---------- 3. 건별 실패 WARN 로그 ----------

	@Test
	void 건별_실패_로그는_예외_종류만_있고_메시지_스택_id_key가_없으며_요약에_실패수가_반영된다(CapturedOutput output) throws Exception {
		User bad = 회원("pbad");
		long idBad = 오래된신청(bad, Duration.ofDays(20));
		오래된신청(회원("pgood"), Duration.ofDays(19));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, idBad);
		실패트리거(bad);

		job.runOnce(지금);

		String 로그 = output.getAll();
		assertThat(로그).contains("허가서 기한 만료 정리: 1건 (실패 1건)");
		String 경고 = 로그.lines().filter(l -> l.contains("허가서 기한 만료 처리 실패")).findFirst().orElseThrow();
		assertThat(경고.substring(경고.indexOf("허가서 기한 만료 처리 실패"))).matches("허가서 기한 만료 처리 실패 1건: \\w+");
		// 하이버네이트가 자기 로그에 DB 오류 메시지를 남기는 것은 이 작업 밖이라, 이 작업(PermitExpiryJob)의 로그 줄만 본다
		String 작업로그 = 로그.lines().filter(l -> l.contains("PermitExpiryJob")).collect(java.util.stream.Collectors.joining(" | "));
		assertThat(작업로그).contains("허가서 기한 만료 처리 실패 1건");
		assertThat(작업로그).doesNotContain("boom-secret").doesNotContain(key).doesNotContain("permits/")
				.doesNotContain("requestId").doesNotContain("Exception:").doesNotContain("userId=" + bad.getId())
				.doesNotContain("id=" + idBad);
		// 이 작업 로그 줄 바로 뒤에 스택 줄(탭 + at)이 이어지지 않는다
		assertThat(로그).doesNotContainPattern("PermitExpiryJob[^\\n]*\\n\\s+at ");
	}

	// ---------- 5. 스케줄 연결 ----------

	@Autowired org.springframework.core.env.Environment env;
	@Autowired com.example.demo.permit.repository.PermitRequestRepository permitRequestRepository;
	@Autowired com.example.demo.permit.service.AdminPermitService adminPermitService;
	@Autowired java.time.Clock clock;

	@Test
	void 스케줄_진입점은_주입된_Clock으로_만료를_처리한다() throws Exception {
		long id = 신청(회원("pa"));
		// 서울 고정 Clock의 현재 기준 15일 전으로 만든다
		생성시각(id, Instant.now(clock).minus(Duration.ofDays(15)));
		ReflectionTestUtils.invokeMethod(job, "scheduledRun");
		assertThat(상태(id)).isEqualTo("REJECTED");
		assertThat(신청행(id).get("reject_reason")).isEqualTo("EXPIRED");
	}

	@Test
	void 스케줄_진입점은_예외가_나도_삼키고_밖으로_던지지_않는다() {
		var 고장난저장소 = org.mockito.Mockito.mock(com.example.demo.permit.repository.PermitRequestRepository.class);
		org.mockito.Mockito.when(고장난저장소.findIdsByStatusAndCreatedAtBefore(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
				.thenThrow(new IllegalStateException("db down"));
		PermitExpiryJob j = new PermitExpiryJob(고장난저장소, adminPermitService, clock, TTL, 10);
		org.assertj.core.api.Assertions.assertThatCode(() -> ReflectionTestUtils.invokeMethod(j, "scheduledRun"))
				.doesNotThrowAnyException();
	}

	@Test
	void 스케줄_속성은_Duration으로_해석되고_기본값이_기대와_같다() throws Exception {
		var m = PermitExpiryJob.class.getDeclaredMethod("scheduledRun");
		var sch = m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
		assertThat(sch).isNotNull();
		assertThat(sch.fixedDelayString()).contains("app.permit.expiry-interval");
		assertThat(sch.initialDelayString()).contains("app.permit.expiry-initial-delay");
		Duration 주기 = Duration.parse(env.resolvePlaceholders(sch.fixedDelayString()));
		Duration 첫지연 = Duration.parse(env.resolvePlaceholders(sch.initialDelayString()));
		assertThat(주기).isEqualTo(Duration.ofHours(1));
		assertThat(첫지연).isEqualTo(Duration.ofMinutes(3));
		assertThat(env.getProperty("app.permit.pending-ttl", Duration.class)).isEqualTo(Duration.ofDays(14));
		assertThat(env.getProperty("app.permit.expiry-batch-size", Integer.class)).isEqualTo(100);
	}

	// ---------- 6. 기동 검사 ----------

	@Test
	void 잘못된_설정이면_생성에_실패한다() {
		for (Duration ttl : List.of(Duration.ZERO, Duration.ofDays(-1))) {
			org.assertj.core.api.Assertions
					.assertThatThrownBy(() -> new PermitExpiryJob(permitRequestRepository, adminPermitService, clock, ttl, 100))
					.isInstanceOf(IllegalArgumentException.class);
		}
		for (int size : new int[] { 0, -5 }) {
			org.assertj.core.api.Assertions
					.assertThatThrownBy(() -> new PermitExpiryJob(permitRequestRepository, adminPermitService, clock, TTL, size))
					.isInstanceOf(IllegalArgumentException.class);
		}
		new PermitExpiryJob(permitRequestRepository, adminPermitService, clock, TTL, 1); // 정상 값은 통과
	}

	// ---------- 7. 기타 ----------

	@Test
	void 사진_key가_없는_오래된_대기_신청도_예외없이_만료된다() throws Exception {
		long id = 오래된신청(회원("pa"), Duration.ofDays(20));
		jdbc.update("update permit_requests set photo_key = null, content_type = null where id = ?", id);
		assertThat(job.runOnce(지금)).isEqualTo(1);
		assertThat(상태(id)).isEqualTo("REJECTED");
		assertThat(대기표수()).isZero();
	}

	@Test
	void 이미_인증된_회원의_오래된_대기_신청도_만료된다() throws Exception {
		User u = 회원("pa");
		long id = 오래된신청(u, Duration.ofDays(20));
		jdbc.update("update users set verified_school_id = ? where id = ?", 연세id, u.getId());
		assertThat(job.runOnce(지금)).isEqualTo(1);
		assertThat(상태(id)).isEqualTo("REJECTED");
		assertThat(인증학교(u)).as("인증은 그대로").isNotNull();
	}

	// ---------- 7. 경쟁 ----------

	@Test
	void 만료와_관리자_승인이_같은_신청에_겹치면_교착없이_하나만_반영되고_일관된다() throws Exception {
		User u = 회원("pa");
		long id = 오래된신청(u, Duration.ofDays(20));

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()),
				List.of(() -> 승인(관리자쿠키, id, 승인본문(연세id, "RACE-1"), true).getStatus(), () -> job.runOnce(지금)));

		int 승인결과 = res.get(0);
		int 만료건수 = res.get(1);
		String 상태 = 상태(id);
		if (상태.equals("APPROVED")) {
			assertThat(승인결과).isEqualTo(200);
			assertThat(만료건수).isZero();
			assertThat(인증학교(u)).isNotNull();
			assertThat(신청행(id).get("decided_by")).isNotNull();
		} else {
			assertThat(상태).isEqualTo("REJECTED");
			assertThat(승인결과).isEqualTo(409);
			assertThat(만료건수).isEqualTo(1);
			assertThat(신청행(id).get("reject_reason")).isEqualTo("EXPIRED");
			assertThat(인증학교(u)).isNull();
		}
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	@Test
	void 만료와_회원_철회가_겹쳐도_교착_500없이_끝나고_runOnce가_멈추지_않는다() throws Exception {
		User u = 회원("pa");
		long id = 오래된신청(u, Duration.ofDays(20));
		var 회원쿠키 = 쿠키(u);

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(() -> job.runOnce(지금), () -> mockMvc
				.perform(delete(ME_URL).cookie(회원쿠키).with(csrf())).andReturn().getResponse().getStatus()));

		assertThat(res.get(0)).as("만료 건수").isIn(0, 1);
		assertThat(res.get(1)).as("철회").isIn(204, 404);
		assertThat(res.get(1)).isNotEqualTo(500);
		// 철회가 먼저면 행이 없고, 만료가 먼저면 REJECTED 한 건이 있다가 철회가 지운다(철회는 REJECTED도 지운다면 0)
		assertThat(jdbc.queryForObject("select count(*) from permit_requests where status = 'PENDING'", Long.class)).isZero();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	@Test
	void 만료와_회원_탈퇴가_겹쳐도_교착_500없이_끝나고_runOnce가_멈추지_않는다() throws Exception {
		User u = 회원("pa");
		오래된신청(u, Duration.ofDays(20));
		var 회원쿠키 = 쿠키(u);

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()),
				List.of(() -> job.runOnce(지금),
						() -> mockMvc.perform(delete("/users/me").with(csrf()).cookie(회원쿠키)
								.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
								.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus()));

		assertThat(res.get(0)).isIn(0, 1);
		assertThat(res.get(1)).isEqualTo(204);
		assertThat(userRepository.findById(u.getId())).isEmpty();
		assertThat(행수()).isZero();
		assertThat(파일수()).isZero();
	}

	// ---------- 8. 건별 실패 격리 ----------

	@Test
	void 한_건_처리에서_예외가_나도_다른_건은_처리되고_runOnce는_정상_종료한다() throws Exception {
		User bad = 회원("pbad");
		User good = 회원("pgood");
		long idBad = 오래된신청(bad, Duration.ofDays(20));
		long idGood = 오래된신청(good, Duration.ofDays(19));
		// 특정 회원 신청을 REJECTED로 바꾸려 하면 DB가 예외를 내는 트리거로 한 건만 실패시킨다
		jdbc.execute("create function fail_expire() returns trigger language plpgsql as $$ begin "
				+ "if new.user_id = " + bad.getId() + " and new.status = 'REJECTED' then raise exception 'boom'; end if; "
				+ "return new; end $$");
		jdbc.execute("create trigger t_fail_expire before update on permit_requests for each row execute function fail_expire()");

		int n = job.runOnce(지금);

		assertThat(n).isEqualTo(1);
		assertThat(상태(idBad)).isEqualTo("PENDING");
		assertThat(신청행(idBad).get("photo_key")).as("롤백됐으니 사진 key 유지").isNotNull();
		assertThat(상태(idGood)).isEqualTo("REJECTED");
		assertThat(파일수()).as("실패 건 사진은 남고 성공 건 사진만 지워짐").isEqualTo(1);
		assertThat(대기표수()).as("롤백된 건의 key가 대기 표에 남으면 청소부가 대기 중 사진을 지운다").isZero();
	}

	// ---------- 9. 창고 삭제 실패 ----------

	@Test
	void 창고_삭제가_실패해도_상태는_REJECTED이고_key는_대기표에_남는다() throws Exception {
		long id = 오래된신청(회원("pa"), Duration.ofDays(20));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);
		창고.지우기실패 = true;

		assertThat(job.runOnce(지금)).isEqualTo(1);

		assertThat(상태(id)).isEqualTo("REJECTED");
		assertThat(신청행(id).get("photo_key")).isNull();
		assertThat(대기표수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select storage_key from photo_delete_queue", String.class)).isEqualTo(key);
	}

	// ---------- 10. 로그 ----------

	@Test
	void 처리하면_건수_로그만_남고_key_permits_회원id_requestId가_없다(CapturedOutput output) throws Exception {
		User a = 회원("pa");
		User b = 회원("pb");
		long ida = 오래된신청(a, Duration.ofDays(20));
		long idb = 오래된신청(b, Duration.ofDays(21));
		String keyA = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, ida);
		String keyB = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, idb);
		창고.지우기실패 = true; // 삭제 실패 경고 로그에서도 새지 않는지 함께 본다

		job.runOnce(지금);

		String 로그 = output.getAll();
		assertThat(로그).contains("허가서 기한 만료 정리: 2건");
		assertThat(로그).doesNotContain(keyA).doesNotContain(keyB).doesNotContain("permits/").doesNotContain("SECRETPATH");
		String 정리줄 = 로그.lines().filter(l -> l.contains("허가서 기한 만료 정리")).findFirst().orElseThrow();
		String 메시지 = 정리줄.substring(정리줄.indexOf("허가서 기한 만료 정리")); // 시각·스레드 부분은 빼고 본문만 본다
		assertThat(메시지).isEqualTo("허가서 기한 만료 정리: 2건 (실패 0건)");
	}

	@Test
	void 처리_대상이_없으면_만료_로그가_없다(CapturedOutput output) throws Exception {
		오래된신청(회원("pa"), Duration.ofDays(1));
		job.runOnce(지금);
		assertThat(output.getAll()).doesNotContain("허가서 기한 만료 정리");
	}
}
