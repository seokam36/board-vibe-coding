package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.repository.StoredPhoto;
import com.example.demo.permit.service.AdminPermitService;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.user.domain.User;

// S20-b 완료 조건 7(창고 삭제 실패해도 승인/반려 200)·8(로그에 민감값 없음)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AdminPermitLogTests extends AdminPermitTestBase {

	@Autowired PhotoDeleteQueue photoDeleteQueue;

	static final String 번호원문 = "ZQ-98 76 54";
	static final String 번호정규화 = "ZQ987654";

	// 삭제만 일부러 실패하는 가짜 창고(예외 메시지에 key와 비밀 경로를 넣어 로그 유출을 시험한다)
	static class 삭제실패창고 implements PhotoStorage {
		final PhotoStorage 진짜;
		volatile boolean 지우기실패;
		volatile boolean 열기실패; // S20-j1: 관리자 사진 열기 실패 시험용
		final java.util.concurrent.atomic.AtomicInteger 열기횟수 = new java.util.concurrent.atomic.AtomicInteger(); // open 호출 횟수

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
			열기횟수.incrementAndGet();
			if (열기실패) {
				throw new PhotoStorageException("열기 실패 " + key + " SECRETPATH-C:/disk/permits", new RuntimeException("원인 " + key));
			}
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

	@Autowired AdminPermitService adminPermitService;

	PhotoStorage 진짜창고;
	삭제실패창고 창고;
	// S20-j1: 관리자 사진 열기도 같은 가짜 창고를 쓰게 서비스 쪽 창고도 바꿔 끼운다(프록시면 진짜 객체에)
	Object 관리자서비스;
	PhotoStorage 관리자서비스_진짜창고;

	@BeforeEach
	void 창고바꿔끼우기() {
		진짜창고 = (PhotoStorage) ReflectionTestUtils.getField(photoDeleteQueue, "photoStorage");
		창고 = new 삭제실패창고(진짜창고);
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 창고);
		관리자서비스 = AopTestUtils.getUltimateTargetObject(adminPermitService);
		관리자서비스_진짜창고 = (PhotoStorage) ReflectionTestUtils.getField(관리자서비스, "photoStorage");
		ReflectionTestUtils.setField(관리자서비스, "photoStorage", 창고);
	}

	@AfterEach
	void 창고되돌리기() {
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 진짜창고);
		ReflectionTestUtils.setField(관리자서비스, "photoStorage", 관리자서비스_진짜창고);
	}

	private void 민감값이_로그에_없다(CapturedOutput output, String key) throws Exception {
		String uuid = key.substring(key.lastIndexOf('/') + 1, key.lastIndexOf('.'));
		String 해시 = permitNumberHash.of(번호정규화);
		assertThat(output.getAll()).doesNotContain(key).doesNotContain(uuid).doesNotContain("permits/")
				.doesNotContain("SECRETPATH").doesNotContain(번호원문).doesNotContain(번호정규화).doesNotContain(해시);
	}

	@Test
	void 승인_로그에는_adminId와_requestId만_있고_번호_해시_key가_없다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);

		assertThat(승인(id, 연세id, 번호원문).getStatus()).isEqualTo(200);

		assertThat(output.getAll()).contains("허가서 승인: adminId=" + 관리자.getId() + ", requestId=" + id);
		민감값이_로그에_없다(output, key);
	}

	@Test
	void 깨진_JSON_본문은_400이고_스프링_기본_경고가_로그에_남지_않는다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		assertThat(승인(관리자쿠키, id, "{\"schoolId\": 1, \"permitNumber\": \"SECRETPATH", true).getStatus()).isEqualTo(400);
		assertThat(output.getAll()).doesNotContain("JSON parse error").doesNotContain("Resolved [").doesNotContain("SECRETPATH");
	}

	// S20-j1: 타입이 안 맞는 값·여는 괄호 하나만 보내도 400이고, 보낸 글자·스프링 기본 경고가 로그에 남지 않는다
	@Test
	void 깨진_JSON_세_가지는_모두_400이고_보낸_글자가_로그에_없다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		assertThat(승인(관리자쿠키, id, "{\"schoolId\":\"" + 번호원문 + "\"}", true).getStatus()).isEqualTo(400);
		assertThat(반려(관리자쿠키, id, "{\"reason\":\"" + 번호원문 + "\"}", true).getStatus()).isEqualTo(400);
		assertThat(승인(관리자쿠키, id, "{", true).getStatus()).isEqualTo(400);
		assertThat(output.getAll()).doesNotContain(번호원문).doesNotContain("HttpMessageNotReadableException")
				.doesNotContain("JSON parse error").doesNotContain("Resolved [");
		// 깨진 요청은 아무것도 바꾸지 않는다
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	// S20-j1: 창고 열기가 실패하면 500이고, 로그에는 예외 이름·id만 있고 key·경로·스택은 없다
	@Test
	void 사진_열기가_실패하면_500이고_로그에_민감값과_스택이_없다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);
		창고.열기실패 = true;

		assertThat(사진(관리자쿠키, id).getStatus()).isEqualTo(500);

		assertThat(output.getAll()).contains("허가서 사진 열기 실패: requestId=" + id).contains("PhotoStorageException")
				.doesNotContain("\tat ").doesNotContain("원인 ");
		민감값이_로그에_없다(output, key);
	}

	// S20-j1: DB에 저장된 사진 종류가 jpeg·png가 아니면(손상·조작) 열지 않고 404, 정상 값이면 그 종류로 200
	@Test
	void 저장된_사진_종류가_jpeg_png가_아니면_404이고_맞으면_그_종류로_200이다() throws Exception {
		long id = 신청(회원("pa"));

		jdbc.update("update permit_requests set content_type = 'text/html' where id = ?", id);
		assertThat(사진(관리자쿠키, id).getStatus()).isEqualTo(404);
		assertThat(창고.열기횟수.get()).as("잘못된 종류는 창고를 열기 전에 막는다").isZero();

		jdbc.update("update permit_requests set content_type = 'not a type' where id = ?", id);
		assertThat(사진(관리자쿠키, id).getStatus()).isEqualTo(404);
		assertThat(창고.열기횟수.get()).isZero();

		jdbc.update("update permit_requests set content_type = 'image/png' where id = ?", id);
		MockHttpServletResponse png = 사진(관리자쿠키, id);
		assertThat(png.getStatus()).isEqualTo(200);
		assertThat(png.getContentType()).isEqualTo("image/png");
		assertThat(창고.열기횟수.get()).isEqualTo(1);

		jdbc.update("update permit_requests set content_type = 'image/jpeg' where id = ?", id);
		MockHttpServletResponse jpeg = 사진(관리자쿠키, id);
		assertThat(jpeg.getStatus()).isEqualTo(200);
		assertThat(jpeg.getContentType()).isEqualTo("image/jpeg");
		assertThat(창고.열기횟수.get()).isEqualTo(2);
	}

	@Test
	void 반려_로그에는_adminId와_requestId만_있고_key가_없다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);

		assertThat(반려(id, "BLURRY").getStatus()).isEqualTo(200);

		assertThat(output.getAll()).contains("허가서 반려: adminId=" + 관리자.getId() + ", requestId=" + id);
		민감값이_로그에_없다(output, key);
	}

	@Test
	void 창고_삭제가_실패해도_승인은_200이고_key는_대기_표에_남고_로그에_민감값이_없다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);
		창고.지우기실패 = true;

		MockHttpServletResponse r = 승인(id, 연세id, 번호원문);

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(신청행(id).get("status")).isEqualTo("APPROVED");
		assertThat(신청행(id).get("photo_key")).isNull();
		assertThat(대기표수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select storage_key from photo_delete_queue", String.class)).isEqualTo(key);
		assertThat(output.getAll()).contains("PhotoStorageException").contains("허가서 승인: adminId=");
		민감값이_로그에_없다(output, key);
	}

	@Test
	void 창고_삭제가_실패해도_반려는_200이고_key는_대기_표에_남고_로그에_민감값이_없다(CapturedOutput output) throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);
		창고.지우기실패 = true;

		MockHttpServletResponse r = 반려(id, "NOT_PERMIT");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(신청행(id).get("status")).isEqualTo("REJECTED");
		assertThat(대기표수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select storage_key from photo_delete_queue", String.class)).isEqualTo(key);
		assertThat(output.getAll()).contains("허가서 반려: adminId=");
		민감값이_로그에_없다(output, key);
	}
}
