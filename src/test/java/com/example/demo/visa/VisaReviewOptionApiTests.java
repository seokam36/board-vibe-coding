package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S11-a 완료 조건 2, 3: GET /visa-review-options 는 로그인 없이(만료·변조 쿠키가 붙어도) 200과 선택지 3묶음을 준다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class VisaReviewOptionApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper objectMapper;

	private List<String> 코드들(JsonNode arr) {
		List<String> l = new ArrayList<>();
		arr.forEach(n -> l.add(n.get("code").asString()));
		return l;
	}

	private List<String> 이름들(JsonNode arr) {
		List<String> l = new ArrayList<>();
		arr.forEach(n -> l.add(n.get("name").asString()));
		return l;
	}

	private void 선택지를_검사(MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
		// 최상위 키는 세 개뿐
		List<String> keys = new ArrayList<>();
		body.propertyNames().forEach(keys::add);
		assertThat(keys).containsExactlyInAnyOrder("visaTypes", "applicationTypes", "results");

		assertThat(코드들(body.get("visaTypes"))).containsExactly("D_2", "D_4", "D_10", "E_7", "OTHER");
		assertThat(코드들(body.get("applicationTypes"))).containsExactly("NEW", "REGISTRATION", "EXTENSION", "CHANGE", "OTHER");
		assertThat(코드들(body.get("results"))).containsExactly("APPROVED", "DENIED");

		assertThat(이름들(body.get("visaTypes"))).contains("D-2 유학");
		assertThat(이름들(body.get("applicationTypes"))).contains("체류기간 연장");
		assertThat(이름들(body.get("results"))).contains("승인");
		// 각 항목은 code·name 두 키만 가진다
		body.get("results").forEach(n -> {
			assertThat(n.size()).isEqualTo(2);
			assertThat(n.get("name").asString()).isNotBlank();
		});
	}

	@Test
	void 로그인_없이_조회하면_200과_선택지_세_묶음을_enum_순서대로_반환한다() throws Exception {
		선택지를_검사(mockMvc.perform(get("/visa-review-options")).andReturn());
	}

	@Test
	void 만료된_access_token_쿠키가_붙어도_200이다() throws Exception {
		Instant past = Instant.now().minusSeconds(3600);
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "x")
				.issuedAt(past).expiresAt(past.plusSeconds(900)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		선택지를_검사(mockMvc.perform(get("/visa-review-options").cookie(new Cookie("access_token", expired))).andReturn());
	}

	@Test
	void 변조된_access_token_쿠키가_붙어도_200이다() throws Exception {
		선택지를_검사(mockMvc.perform(get("/visa-review-options").cookie(new Cookie("access_token", "aaa.bbb.ccc"))).andReturn());
	}
}
