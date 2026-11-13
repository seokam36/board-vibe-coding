package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
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

	// 관리자 대화 보기 요청 (쿠키가 null이면 비로그인). 신고 id는 문자열도 받아 잘못된 id를 시험할 수 있다.
	static MockHttpServletResponse 보기(MockMvc mockMvc, Cookie 쿠키, Object 신고id) throws Exception {
		var req = get("/admin/reports/" + 신고id + "/conversation");
		if (쿠키 != null) {
			req.cookie(쿠키);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	// 응답 본문을 JSON 트리로 읽는다.
	static JsonNode json(ObjectMapper om, MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	// 해당 방을 신고(ABUSE)하고 만들어진 신고 id를 돌려준다. 201이 아니면 준비 실패로 본다.
	static long 대화_신고(MockMvc mockMvc, ObjectMapper om, Cookie 쿠키, long 방id) throws Exception {
		MockHttpServletResponse res = 신고(mockMvc, 쿠키, 방id, 본문("ABUSE"));
		assertThat(res.getStatus()).as("준비용 대화 신고").isEqualTo(201);
		return json(om, res).get("id").asLong();
	}

	// 방에 답장 쪽지를 보낸다. jsonContent는 이미 JSON 문자열 형태(따옴표 포함)로 넘긴다.
	static void 답장(MockMvc mockMvc, Cookie 쿠키, long 방id, String jsonContent) throws Exception {
		int st = mockMvc.perform(post("/conversations/" + 방id + "/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":" + jsonContent + "}").cookie(쿠키)).andReturn().getResponse().getStatus();
		assertThat(st).as("준비용 답장").isEqualTo(201);
	}

	// 회원 탈퇴(204).
	static void 탈퇴(MockMvc mockMvc, Cookie 쿠키) throws Exception {
		int st = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(st).as("준비용 탈퇴").isEqualTo(204);
	}
}
