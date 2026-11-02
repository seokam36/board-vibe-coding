package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S2-a 게시글 수정(PUT)·삭제(DELETE) API 완료 조건 검사
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PostUpdateDeleteApiTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PostRepository postRepository;

	@Autowired
	ObjectMapper objectMapper;

	@BeforeEach
	void 정리() {
		postRepository.deleteAll();
	}

	private JsonNode 본문(MvcResult r) throws Exception {
		return objectMapper.readTree(r.getResponse().getContentAsString());
	}

	private JsonNode 작성() throws Exception {
		return 본문(mockMvc.perform(post("/posts").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"원래제목\",\"content\":\"원래본문\"}")).andReturn());
	}

	private MvcResult 수정(Object id, String json) throws Exception {
		return mockMvc.perform(put("/posts/" + id).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	// 조건 1: 수정 응답이 200이고 id·createdAt은 유지, 제목·본문만 바뀐다
	@Test
	void 수정시_200과_바뀐_제목_본문을_반환하고_id와_createdAt은_유지된다() throws Exception {
		JsonNode created = 작성();
		MvcResult r = 수정(created.get("id").asLong(), "{\"title\":\"수정\",\"content\":\"수정본문\"}");
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = 본문(r);
		assertThat(body.get("id").asLong()).isEqualTo(created.get("id").asLong());
		assertThat(body.get("title").asString()).isEqualTo("수정");
		assertThat(body.get("content").asString()).isEqualTo("수정본문");
		assertThat(body.get("createdAt").asString()).isEqualTo(created.get("createdAt").asString());
	}

	// 조건 2: 수정이 DB에 실제 반영됐는지 GET으로 확인
	@Test
	void 수정후_단건_조회하면_수정된_값이_보인다() throws Exception {
		JsonNode created = 작성();
		long id = created.get("id").asLong();
		수정(id, "{\"title\":\"수정\",\"content\":\"수정본문\"}");
		JsonNode body = 본문(mockMvc.perform(get("/posts/" + id)).andReturn());
		assertThat(body.get("title").asString()).isEqualTo("수정");
		assertThat(body.get("content").asString()).isEqualTo("수정본문");
		// PUT 응답은 메모리 값이라, DB에 저장된 createdAt이 불변인지는 GET으로 확인한다
		assertThat(body.get("createdAt").asString()).isEqualTo(created.get("createdAt").asString());
	}

	// 조건 3
	@Test
	void 없는_게시글_수정시_404를_반환한다() throws Exception {
		assertThat(수정(999999, "{\"title\":\"수정\",\"content\":\"수정본문\"}").getResponse().getStatus()).isEqualTo(404);
	}

	// 조건 4: 잘못된 본문은 모두 400이고 원본 데이터가 변하지 않아야 한다
	private void 잘못된_수정은_400이고_원본_유지(String json) throws Exception {
		long id = 작성().get("id").asLong();
		assertThat(수정(id, json).getResponse().getStatus()).as(json).isEqualTo(400);
		JsonNode body = 본문(mockMvc.perform(get("/posts/" + id)).andReturn());
		assertThat(body.get("title").asString()).isEqualTo("원래제목");
		assertThat(body.get("content").asString()).isEqualTo("원래본문");
	}

	@Test
	void 빈_title_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"\",\"content\":\"c\"}");
	}

	@Test
	void 공백뿐인_title_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"   \",\"content\":\"c\"}");
	}

	@Test
	void title_누락_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"content\":\"c\"}");
	}

	@Test
	void 길이_101자_title_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"" + "a".repeat(101) + "\",\"content\":\"c\"}");
	}

	@Test
	void NUL_포함_title_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"a\u0000b\",\"content\":\"c\"}");
	}

	@Test
	void 빈_content_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"t\",\"content\":\"\"}");
	}

	@Test
	void 공백뿐인_content_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"t\",\"content\":\"   \"}");
	}

	@Test
	void content_누락_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"t\"}");
	}

	@Test
	void NUL_포함_content_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":\"t\",\"content\":\"a\u0000b\"}");
	}

	@Test
	void 깨진_JSON_수정은_400이고_원본이_유지된다() throws Exception {
		잘못된_수정은_400이고_원본_유지("{\"title\":");
	}

	// 조건 5: 삭제 204 + 빈 본문, 이후 조회 404
	@Test
	void 삭제시_204와_빈_본문을_반환하고_이후_조회는_404다() throws Exception {
		long id = 작성().get("id").asLong();
		MvcResult r = mockMvc.perform(delete("/posts/" + id)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(204);
		assertThat(r.getResponse().getContentAsString()).isEmpty();
		assertThat(mockMvc.perform(get("/posts/" + id)).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	// 조건 6
	@Test
	void 없는_게시글_삭제시_404를_반환한다() throws Exception {
		assertThat(mockMvc.perform(delete("/posts/999999")).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void 숫자가_아닌_id로_수정하면_400을_반환한다() throws Exception {
		assertThat(수정("abc", "{\"title\":\"t\",\"content\":\"c\"}").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void 숫자가_아닌_id로_삭제하면_400을_반환한다() throws Exception {
		assertThat(mockMvc.perform(delete("/posts/abc")).andReturn().getResponse().getStatus()).isEqualTo(400);
	}
}
