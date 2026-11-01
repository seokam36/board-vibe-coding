package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 프로퍼티로 허용 origin을 바꿨을 때 그 값이 적용되는지 검사한다
@SpringBootTest(properties = "app.cors.allowed-origins=http://other.test, http://second.test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class CorsPropertyTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 프로퍼티로_지정한_origin만_Allow_Origin을_받는다() throws Exception {
		mockMvc.perform(get("/posts").header("Origin", "http://other.test"))
				.andExpect(header().string("Access-Control-Allow-Origin", "http://other.test"));
	}

	@Test
	void 프로퍼티에_없는_기본_origin은_Allow_Origin을_받지_못한다() throws Exception {
		mockMvc.perform(get("/posts").header("Origin", "http://localhost:5173"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	// 쉼표 뒤 공백이 있어도 두 번째 origin이 허용되어야 한다
	@Test
	void 쉼표_뒤_공백이_있어도_두번째_origin이_Allow_Origin을_받는다() throws Exception {
		mockMvc.perform(get("/posts").header("Origin", "http://second.test"))
				.andExpect(header().string("Access-Control-Allow-Origin", "http://second.test"));
	}
}
