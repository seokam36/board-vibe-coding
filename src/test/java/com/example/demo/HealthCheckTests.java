package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class) // 테스트용 PostgreSQL 컨테이너를 사용하도록 설정을 가져온다
@AutoConfigureMockMvc
class HealthCheckTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 헬스체크는_200과_정확히_status_UP만_반환한다() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
	}

	// S4-a: 허용 목록 밖 경로는 "그 밖은 로그인 필요" 규칙 때문에 404가 아니라 401이 된다 (경로 존재 여부를 숨기는 효과도 있음)
	@Test
	void 로그인_없이_없는_경로_조회시_401을_반환한다() throws Exception {
		mockMvc.perform(get("/no-such-path"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 로그인_없이_노출하지_않은_actuator_env_조회시_401을_반환한다() throws Exception {
		mockMvc.perform(get("/actuator/env"))
				.andExpect(status().isUnauthorized());
	}
}
