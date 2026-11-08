package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// 리뷰 반영: 같은 글·댓글을 동시에 지울 때 늦은 쪽이 500이 아니라 404인지 확인한다.
// 수정 전 코드(postRepository.delete(post))였다면: 요청이 락에서 기다리다 깨어나 0행 삭제를 감지하고
// ObjectOptimisticLockingFailureException -> 500이 났을 경로다. 지금은 JPQL 한 문장 삭제가 0행을 돌려줘 404여야 한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class DeleteRaceTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	CommentRepository commentRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	TokenService tokenService;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager txManager;

	User 관리자;
	User 글쓴이;
	Cookie 관리자쿠키;
	Cookie 글쓴이쿠키;
	Post 글;
	Comment 댓글;

	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		정리();
		관리자 = TestLogin.관리자(userRepository, jdbc, "admin");
		글쓴이 = TestLogin.사용자(userRepository, "writer");
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		글쓴이쿠키 = TestLogin.쿠키(tokenService, 글쓴이);
		글 = postRepository.save(new Post("제목", "내용", 글쓴이, null, PostCategory.CHAT));
		댓글 = commentRepository.save(new Comment(글, 글쓴이, "댓글"));
	}

	private int 글삭제(Cookie c, Long id) throws Exception {
		return mockMvc.perform(delete("/posts/" + id).with(csrf()).cookie(c)).andReturn().getResponse().getStatus();
	}

	private int 댓글삭제(Cookie c, Long id) throws Exception {
		return mockMvc.perform(delete("/comments/" + id).with(csrf()).cookie(c)).andReturn().getResponse().getStatus();
	}

	private long 글행수(Long id) {
		return jdbc.queryForObject("select count(*) from posts where id = ?", Long.class, id);
	}

	private long 댓글행수(Long id) {
		return jdbc.queryForObject("select count(*) from comments where id = ?", Long.class, id);
	}

	@Test
	void 관리자가_글을_지우는_동안_다른_쪽이_먼저_지우고_커밋하면_404이고_글_행이_없다() throws Exception {
		Long pid = 글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from posts where id = ?", pid), // 커밋 전 삭제 = 글 행 잠금
				() -> 글삭제(관리자쿠키, pid), () -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(글행수(pid)).isZero();
	}

	@Test
	void 본인이_글을_지우는_동안_다른_쪽이_먼저_지우고_커밋하면_404이고_글_행이_없다() throws Exception {
		Long pid = 글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from posts where id = ?", pid), () -> 글삭제(글쓴이쿠키, pid), () -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(글행수(pid)).isZero();
	}

	@Test
	void 관리자가_댓글을_지우는_동안_다른_쪽이_먼저_지우고_커밋하면_404이고_댓글_행이_없다() throws Exception {
		Long cid = 댓글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from comments where id = ?", cid), () -> 댓글삭제(관리자쿠키, cid), () -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(댓글행수(cid)).isZero();
		assertThat(글행수(글.getId())).as("글은 그대로").isEqualTo(1);
	}

	@Test
	void 본인이_댓글을_지우는_동안_다른_쪽이_먼저_지우고_커밋하면_404이고_댓글_행이_없다() throws Exception {
		Long cid = 댓글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from comments where id = ?", cid), () -> 댓글삭제(글쓴이쿠키, cid), () -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(댓글행수(cid)).isZero();
	}
}
