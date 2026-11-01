package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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

	@Test
	void 없는_경로_조회시_404를_반환한다() throws Exception {
		mockMvc.perform(get("/no-such-path"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 노출하지_않은_actuator_env_조회시_404를_반환한다() throws Exception {
		mockMvc.perform(get("/actuator/env"))
				.andExpect(status().isNotFound());
	}
}
