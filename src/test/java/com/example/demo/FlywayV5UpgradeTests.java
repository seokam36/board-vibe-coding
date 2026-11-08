package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 완료 조건 (S9-b-1): V4까지 적용되고 회원·글·댓글이 미리 있는 DB에 V5를 적용하면
// 기존 데이터가 유지되고, reports 표와 제약이 생기며, 제약이 이상한 값을 막고, status 기본값이 PENDING인지 확인한다.
// 주의: 다른 테스트와 얽히지 않도록 전체 개수가 아니라 "내가 넣은 행(prefix 'old_')"만 본다.
class FlywayV5UpgradeTests {

	static PostgreSQLContainer postgres;
	static long userId;
	static long postId;
	static long commentId;

	private static Flyway flyway(String target) {
		return Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.target(target)
				.load();
	}

	@BeforeAll
	static void 준비() throws SQLException {
		postgres = new PostgreSQLContainer("postgres:17");
		postgres.start();
		// 1) V4까지만 적용 (reports 표가 없는 상태)
		flyway("4").migrate();
		// 2) 기존 회원·글·댓글 넣기
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'old_a', 'old_a')");
		userId = 단일값("SELECT id FROM users WHERE username = 'old_a'");
		run("INSERT INTO posts (title, content, created_at, author_id, category) VALUES ('old_post', 'c', now(), " + userId + ", 'CHAT')");
		postId = 단일값("SELECT id FROM posts WHERE title = 'old_post'");
		run("INSERT INTO comments (post_id, author_id, content, created_at) VALUES (" + postId + ", " + userId + ", 'old_comment', now())");
		commentId = 단일값("SELECT id FROM comments WHERE content = 'old_comment'");
		// 3) V5 적용
		flyway("latest").migrate();
	}

	@AfterAll
	static void 정리() {
		postgres.stop();
	}

	private static void run(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	// 첫 칸 숫자 하나를 가져온다 (id 같은 값)
	private static long 단일값(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	// 개수만 세는 조회 (count(*))
	private static int count(String sql) throws SQLException {
		return (int) 단일값(sql);
	}

	// 조회 결과 첫 칸 문자열들을 모은다 (인덱스 이름 목록용)
	private static List<String> 문자열목록(String sql) throws SQLException {
		List<String> result = new ArrayList<>();
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				result.add(rs.getString(1));
			}
		}
		return result;
	}

	private static String insertReport(String postCol, String commentCol, String reason) {
		return "INSERT INTO reports (post_id, comment_id, reporter_id, reason, created_at) VALUES (" + postCol + ", "
				+ commentCol + ", " + userId + ", '" + reason + "', now())";
	}

	@Test
	void V5를_적용해도_기존_회원_글_댓글이_그대로_남는다() throws SQLException {
		assertThat(count("SELECT count(*) FROM users WHERE username = 'old_a'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM posts WHERE title = 'old_post'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM comments WHERE content = 'old_comment'")).isEqualTo(1);
	}

	@Test
	void V5를_적용하면_reports_표와_제약_이름이_생긴다() throws SQLException {
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'reports'")).isEqualTo(1);
		for (String name : new String[] { "fk_reports_post", "fk_reports_comment", "fk_reports_reporter",
				"uk_reports_post_reporter", "uk_reports_comment_reporter", "ck_reports_target", "ck_reports_reason",
				"ck_reports_status" }) {
			assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = '" + name + "'")).as("제약 " + name).isEqualTo(1);
		}
	}

	// 필요한 인덱스만 있고 중복 인덱스(idx_reports_comment_id)는 없다
	@Test
	void reports_표의_인덱스_목록이_기대대로다() throws SQLException {
		List<String> names = 문자열목록("SELECT indexname FROM pg_indexes WHERE tablename = 'reports'");
		assertThat(names).contains("idx_reports_status_id", "idx_reports_reporter_id")
				.doesNotContain("idx_reports_comment_id");
	}

	@Test
	void 글과_댓글_둘_다_비우면_ck_reports_target이_거부한다() {
		assertThatThrownBy(() -> run(insertReport("NULL", "NULL", "SPAM"))).hasMessageContaining("ck_reports_target");
	}

	@Test
	void 글과_댓글_둘_다_채우면_ck_reports_target이_거부한다() {
		assertThatThrownBy(() -> run(insertReport("" + postId, "" + commentId, "SPAM"))).hasMessageContaining("ck_reports_target");
	}

	@Test
	void 허용되지_않는_사유는_ck_reports_reason이_거부한다() {
		assertThatThrownBy(() -> run(insertReport("" + postId, "NULL", "XXX"))).hasMessageContaining("ck_reports_reason");
	}

	@Test
	void 허용되지_않는_상태는_ck_reports_status가_거부한다() {
		assertThatThrownBy(() -> run("INSERT INTO reports (post_id, reporter_id, reason, status, created_at) VALUES ("
				+ postId + ", " + userId + ", 'SPAM', 'DONE', now())")).hasMessageContaining("ck_reports_status");
	}

	@Test
	void status를_안_넣으면_기본값_PENDING이다() throws SQLException {
		// 댓글 대상으로 넣어 다른 테스트의 글 대상 행과 UNIQUE가 겹치지 않게 한다
		run(insertReport("NULL", "" + commentId, "OTHER"));
		assertThat(count("SELECT count(*) FROM reports WHERE comment_id = " + commentId + " AND status = 'PENDING'")).isEqualTo(1);
	}

	@Test
	void V5가_성공_기록으로_한_번_남는다() throws SQLException {
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success = true")).isEqualTo(1);
	}
}
