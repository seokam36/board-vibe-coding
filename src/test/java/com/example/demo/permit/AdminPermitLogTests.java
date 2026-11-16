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
import org.springframework.test.util.ReflectionTestUtils;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.repository.StoredPhoto;
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

	PhotoStorage 진짜창고;
	삭제실패창고 창고;

	@BeforeEach
	void 창고바꿔끼우기() {
		진짜창고 = (PhotoStorage) ReflectionTestUtils.getField(photoDeleteQueue, "photoStorage");
		창고 = new 삭제실패창고(진짜창고);
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 창고);
	}

	@AfterEach
	void 창고되돌리기() {
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 진짜창고);
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
