package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

// 대화 신고 테스트들이 같이 쓰는 준비 도우미(S14-f). 실제 API로 중고 글과 대화방을 만든다.
final class ConversationReportFixture {

	private ConversationReportFixture() {
	}

	// 판매자가 중고 글을 올리고, 구매자가 첫 쪽지를 보내 방을 만든다. {글id, 방id}를 돌려준다.
	static long[] 글과_방_만들기(MockMvc mockMvc, ObjectMapper om, Cookie 판매자쿠키, Cookie 구매자쿠키, String 제목) throws Exception {
		String body = "{\"title\":\"" + 제목 + "\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 중고 글 올리기").isEqualTo(201);
		long 글id = om.readTree(r.getContentAsString()).get("id").asLong();
		MockHttpServletResponse s = mockMvc.perform(post("/market-items/" + 글id + "/conversations").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"첫째\"}").cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(s.getStatus()).as("준비용 첫 쪽지").isEqualTo(201);
		long 방id = om.readTree(s.getContentAsString()).get("conversationId").asLong();
		return new long[] { 글id, 방id };
	}

	// 대화방 신고 요청 (쿠키가 null이면 비로그인). 전체 응답을 돌려준다.
	static MockHttpServletResponse 신고(MockMvc mockMvc, Cookie 쿠키, Object 방id, String 본문) throws Exception {
		var req = post("/conversations/" + 방id + "/reports").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(본문);
		if (쿠키 != null) {
			req.cookie(쿠키);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	static String 본문(String reason) {
		return "{\"reason\":\"" + reason + "\"}";
	}
}
