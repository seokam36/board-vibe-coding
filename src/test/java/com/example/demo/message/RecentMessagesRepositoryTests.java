package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.message.domain.Message;
import com.example.demo.message.dto.MessageResponse;
import com.example.demo.message.repository.MessageRepository;
import com.example.demo.message.service.RecentMessages;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

// S14-g: "DB에서 정말 201개만 꺼내는가"를 저장소 수준에서 검사한다(쪽지 250통을 넣고 Limit.of(201)로 읽기).
// 응답 크기만 보면 서비스가 자른 건지 DB가 자른 건지 알 수 없어서, 저장소 메서드를 직접 부른다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RecentMessagesRepositoryTests {

	@Autowired MockMvc mockMvc;
	@Autowired MessageRepository messageRepository;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;

	User 판매자, 구매자;
	long 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rrseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rrbuyer", "연세대학교");
		Cookie 판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		Cookie 구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		String body = "{\"title\":\"저장소\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글").isEqualTo(201);
		long 글id = om.readTree(r.getContentAsString()).get("id").asLong();
		MockHttpServletResponse s = mockMvc.perform(post("/market-items/" + 글id + "/conversations").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"첫째\"}").cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(s.getStatus()).as("준비용 첫 쪽지").isEqualTo(201);
		방id = om.readTree(s.getContentAsString()).get("conversationId").asLong();
		// 첫 쪽지 포함 총 250통이 되게 채운다
		jdbc.update("insert into messages (conversation_id, sender_id, content, created_at) "
				+ "select ?, ?, 'm' || g, now() from generate_series(2, 250) g", 방id, 판매자.getId());
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// 무엇: findRecentWithSender가 250통 중 201개만, 가장 큰 id부터 돌려준다
	@Test
	void findRecentWithSender는_201개만_꺼내고_첫_원소가_가장_큰_id다() {
		Long 최대id = jdbc.queryForObject("select max(id) from messages where conversation_id = ?", Long.class, 방id);
		List<Message> rows = messageRepository.findRecentWithSender(방id, Limit.of(RecentMessages.FETCH_SIZE));
		assertThat(rows).hasSize(201);
		assertThat(rows.get(0).getId()).isEqualTo(최대id);
		assertThat(rows).extracting(Message::getId).isSortedAccordingTo(java.util.Comparator.reverseOrder());
	}

	// 무엇: findRecentResponses도 201개만, 가장 큰 id부터 돌려준다
	@Test
	void findRecentResponses는_201개만_꺼내고_첫_원소가_가장_큰_id다() {
		Long 최대id = jdbc.queryForObject("select max(id) from messages where conversation_id = ?", Long.class, 방id);
		List<MessageResponse> rows = messageRepository.findRecentResponses(방id, 구매자.getId(), Limit.of(RecentMessages.FETCH_SIZE));
		assertThat(rows).hasSize(201);
		assertThat(rows.get(0).id()).isEqualTo(최대id);
		assertThat(rows).extracting(MessageResponse::id).isSortedAccordingTo(java.util.Comparator.reverseOrder());
	}
}
