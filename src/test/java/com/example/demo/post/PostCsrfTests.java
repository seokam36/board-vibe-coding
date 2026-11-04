package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.domain.Post;
import com.example.demo.post.repository.PostRepository;

// 글 API는 CSRF 토큰 없이 쓰기(POST/PUT/DELETE) 요청을 보내면 403이고 데이터가 바뀌지 않아야 한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PostCsrfTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PostRepository postRepository;

	@AfterEach
	void 정리() {
		postRepository.deleteAll();
	}

	@Test
	void 토큰_없는_POST는_403이고_글이_생기지_않는다() throws Exception {
		mockMvc.perform(post("/posts").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"제목\",\"content\":\"본문\"}"))
				.andExpect(status().isForbidden());
		assertThat(postRepository.count()).isZero();
	}

	@Test
	void 토큰_없는_PUT은_403이고_글이_바뀌지_않는다() throws Exception {
		Long id = postRepository.save(new Post("원래제목", "원래본문")).getId();
		mockMvc.perform(put("/posts/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"바뀐제목\",\"content\":\"바뀐본문\"}"))
				.andExpect(status().isForbidden());
		Post saved = postRepository.findById(id).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("원래제목");
		assertThat(saved.getContent()).isEqualTo("원래본문");
	}

	@Test
	void 토큰_없는_DELETE는_403이고_글이_남아_있다() throws Exception {
		Long id = postRepository.save(new Post("원래제목", "원래본문")).getId();
		mockMvc.perform(delete("/posts/" + id)).andExpect(status().isForbidden());
		assertThat(postRepository.existsById(id)).isTrue();
	}
}
