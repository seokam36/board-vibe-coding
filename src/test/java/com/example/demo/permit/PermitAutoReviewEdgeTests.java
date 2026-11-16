package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

// S20-d2 경계 테스트: 동시 자동 승인 경쟁, 자동 승인 대 관리자 처리·탈퇴, 하루 횟수, 자동 승인 뒤 글쓰기.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
class PermitAutoReviewEdgeTests extends AdminPermitTestBase {

	@Autowired FakePermitTextReader 판독기;

	static final String 번호원문 = "제 2013-0001 호";
	static final String 번호정규화 = "20130001";

	@BeforeEach
	void 판독기초기화() {
		판독기.reset();
	}

	private static String 완벽(String 학교, String 번호) {
		return String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): " + 학교,
				"표준입학허가서 번호(Certificate of Admission No.): " + 번호);
	}

	private MockHttpServletResponse 제출(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private Map<String, Object> 최근행(User u) {
		return jdbc.queryForMap("select * from permit_requests where user_id = ? order by id desc limit 1", u.getId());
	}

	private String 오늘횟수(User u) {
		return redis.opsForValue().get("permit-upload-day:" + u.getId() + ":" + LocalDate.now(ZoneId.of("Asia/Seoul")));
	}

	// ---- 동시 자동 승인 경쟁 ----

	// 결정적 재현: 다른 트랜잭션이 같은 번호로 "승인 UPDATE"를 아직 커밋하지 않은 채 쥐고 있으면
	// 내 자동 승인은 미리 확인(exists)을 통과하고 회원 칸까지 채운 뒤 UNIQUE 대기 -> 상대가 커밋하면 UNIQUE 위반으로 진다.
	// 이때 회원 칸 채우기까지 통째로 롤백돼 verified_school_id가 NULL로 남고, 사진·대기 표가 온전해야 한다.
	@Test
	void 같은_번호_동시_승인에서_진_회원은_PENDING_NUMBER_DUPLICATE이고_인증_칸은_NULL이다() throws Exception {
		User 상대 = 회원("holder");
		long 상대신청 = 신청(상대); // 판독기 text가 null이라 UNREADABLE로 대기
		판독기.text = 완벽("연세대학교", 번호원문);
		User 진쪽 = 회원("loser");
		String 해시 = permitNumberHash.of(번호정규화);

		Callable<Integer> 요청 = () -> 제출(진쪽).getStatus();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("update permit_requests set status = 'APPROVED', permit_number_hash = ?, school_id = ?, "
						+ "photo_key = photo_key where id = ?", 해시, 연세id, 상대신청),
				요청, () -> {
				});

		assertThat(status).isEqualTo(201);
		Map<String, Object> row = 최근행(진쪽);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("NUMBER_DUPLICATE");
		assertThat(row.get("auto_decided")).isEqualTo(false);
		assertThat(row.get("permit_number_hash")).isNull();
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(인증학교(진쪽)).isNull(); // 회원 칸 채우기가 롤백됐다
		assertThat(파일수()).isEqualTo(2); // 두 신청의 사진이 그대로 있다(상대는 위 UPDATE에서 사진 칸을 건드리지 않았다)
		assertThat(대기표수()).isZero(); // 롤백돼 대기 표에 key가 남지 않는다(청소부가 PENDING 사진을 지울 일이 없다)
	}

	// 자연 경쟁: 두 회원이 같은 번호로 동시에 신청, 판독 직후 같이 출발. 어느 쪽이 이겨도 하나만 APPROVED.
	@Test
	void 두_회원이_같은_번호로_동시에_신청하면_하나만_승인되고_진_쪽은_대기로_남는다() throws Exception {
		for (int round = 0; round < 3; round++) {
			기본정리();
			판독기.reset();
			판독기.text = 완벽("연세대학교", 번호원문);
			CyclicBarrier 출발선 = new CyclicBarrier(2);
			판독기.onRead = () -> {
				try {
					출발선.await(10, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			};
			User a = 회원("race" + round + "a");
			User b = 회원("race" + round + "b");

			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Integer> res = new ArrayList<>();
			try {
				Future<Integer> fa = pool.submit(() -> 제출(a).getStatus());
				Future<Integer> fb = pool.submit(() -> 제출(b).getStatus());
				res.add(fa.get(20, TimeUnit.SECONDS));
				res.add(fb.get(20, TimeUnit.SECONDS));
			} finally {
				pool.shutdown();
			}

			assertThat(res).as("라운드 " + round).containsExactly(201, 201);
			assertThat(jdbc.queryForObject("select count(*) from permit_requests where status = 'APPROVED'", Long.class)).isEqualTo(1);
			Map<String, Object> ra = 최근행(a);
			Map<String, Object> rb = 최근행(b);
			Map<String, Object> 승자 = "APPROVED".equals(ra.get("status")) ? ra : rb;
			Map<String, Object> 패자 = 승자 == ra ? rb : ra;
			User 패자회원 = 승자 == ra ? b : a;
			User 승자회원 = 승자 == ra ? a : b;
			assertThat(승자.get("auto_decided")).isEqualTo(true);
			assertThat(승자.get("photo_key")).isNull();
			assertThat(인증학교(승자회원)).isEqualTo(연세id);
			assertThat(패자.get("status")).isEqualTo("PENDING");
			assertThat(패자.get("machine_result")).isEqualTo("NUMBER_DUPLICATE");
			assertThat(패자.get("photo_key")).isNotNull();
			assertThat(인증학교(패자회원)).isNull();
			assertThat(파일수()).isEqualTo(1);
			assertThat(대기표수()).isZero();
		}
	}

	// ---- 자동 승인 대 관리자 처리 / 탈퇴 ----

	@Test
	void 판독_도중_관리자가_승인하면_자동_승인은_물러나고_관리자_승인이_유지된다() throws Exception {
		User u = 회원("adminfirst");
		판독기.text = 완벽("연세대학교", 번호원문);
		판독기.onRead = () -> {
			long id = jdbc.queryForObject("select id from permit_requests where user_id = ?", Long.class, u.getId());
			try {
				assertThat(승인(id, 고려id, "ADMINNUM-1").getStatus()).isEqualTo(200);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat(row.get("auto_decided")).isEqualTo(false);
		assertThat(row.get("decided_by")).isEqualTo(관리자.getId());
		assertThat(row.get("school_id")).isEqualTo(고려id); // 기계가 덮어쓰지 않는다
		assertThat(인증학교(u)).isEqualTo(고려id);
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	@Test
	void 판독_도중_관리자가_반려하면_자동_승인하지_않고_반려가_유지된다() throws Exception {
		User u = 회원("rejectfirst");
		판독기.text = 완벽("연세대학교", 번호원문);
		판독기.onRead = () -> {
			long id = jdbc.queryForObject("select id from permit_requests where user_id = ?", Long.class, u.getId());
			try {
				assertThat(반려(id, "BLURRY").getStatus()).isEqualTo(200);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("REJECTED");
		assertThat(row.get("auto_decided")).isEqualTo(false);
		assertThat(row.get("permit_number_hash")).isNull();
		assertThat(인증학교(u)).isNull();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	@Test
	void 판독_도중_회원이_탈퇴하면_조용히_끝나고_201이며_흔적이_없다() throws Exception {
		User u = 회원("leave");
		판독기.text = 완벽("연세대학교", 번호원문);
		판독기.onRead = () -> {
			try {
				CompletableFuture.runAsync(() -> {
					try {
						int s = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키(u)).contentType(MediaType.APPLICATION_JSON)
								.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
						assertThat(s).isEqualTo(204);
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}).get(15, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(userRepository.findById(u.getId())).isEmpty();
		assertThat(행수()).isZero();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	// ---- 하루 3회 카운트 ----

	@Test
	void 성공한_신청은_판정_결과와_상관없이_하루_횟수에_1씩_센다() throws Exception {
		// 못 읽어 대기 -> 1, 철회 뒤 다시 자동 승인 -> 2
		User u = 회원("count1");
		판독기.text = null;
		assertThat(제출(u).getStatus()).isEqualTo(201);
		assertThat(오늘횟수(u)).isEqualTo("1");
		assertThat(mockMvc.perform(delete(ME_URL).cookie(쿠키(u)).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(204);
		판독기.text = 완벽("연세대학교", 번호원문);
		assertThat(json(제출(u)).get("status").asText()).isEqualTo("APPROVED");
		assertThat(오늘횟수(u)).isEqualTo("2");

		// 한 번에 자동 승인된 다른 회원은 1
		판독기.text = 완벽("고려대학교", "2014-7777");
		User v = 회원("count2");
		assertThat(json(제출(v)).get("status").asText()).isEqualTo("APPROVED");
		assertThat(오늘횟수(v)).isEqualTo("1");
	}

	// ---- 자동 승인 뒤 글쓰기 ----

	@Test
	void 자동_승인된_회원은_해당_학교_게시판에_글을_쓸_수_있다() throws Exception {
		User u = 회원("writer");
		String 본문 = "{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}";
		assertThat(mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키(u)).contentType(MediaType.APPLICATION_JSON).content(본문))
				.andReturn().getResponse().getStatus()).as("승인 전").isEqualTo(403);

		판독기.text = 완벽("연세대학교", 번호원문);
		assertThat(json(제출(u)).get("status").asText()).isEqualTo("APPROVED");

		assertThat(mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키(u)).contentType(MediaType.APPLICATION_JSON).content(본문))
				.andReturn().getResponse().getStatus()).as("승인 후").isEqualTo(201);
	}
}
