package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 완료 조건 (S11-a 4): V6까지 적용되고 회원·글·댓글이 미리 있는 DB에 V7을 적용해도 기존 데이터 수가 바뀌지 않는다.
class FlywayV7UpgradeTests {

	static PostgreSQLContainer postgres;
	static long 회원수, 글수, 댓글수;

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
		// 1) V6까지만 적용하고 기존 데이터를 넣는다
		flyway("6").migrate();
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'old_a', 'old_a')");
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'old_b', 'old_b')");
		long userId = 값("SELECT id FROM users WHERE username = 'old_a'");
		run("INSERT INTO posts (title, content, created_at, author_id, category) VALUES ('old_post', 'c', now(), " + userId + ", 'CHAT')");
		long postId = 값("SELECT id FROM posts WHERE title = 'old_post'");
		run("INSERT INTO comments (content, created_at, author_id, post_id) VALUES ('old_comment', now(), " + userId + ", " + postId + ")");
		회원수 = 값("SELECT count(*) FROM users");
		글수 = 값("SELECT count(*) FROM posts");
		댓글수 = 값("SELECT count(*) FROM comments");
		// 2) V7 적용
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

	private static long 값(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	@Test
	void V7이_성공_기록으로_한_번_남는다() throws SQLException {
		assertThat(값("SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success = true")).isEqualTo(1);
	}

	@Test
	void V7을_적용해도_기존_회원_글_댓글_수가_바뀌지_않는다() throws SQLException {
		assertThat(값("SELECT count(*) FROM users")).as("회원 수").isEqualTo(회원수);
		assertThat(값("SELECT count(*) FROM posts")).as("글 수").isEqualTo(글수);
		assertThat(값("SELECT count(*) FROM comments")).as("댓글 수").isEqualTo(댓글수);
		assertThat(회원수).isEqualTo(2);
		assertThat(글수).isEqualTo(1);
		assertThat(댓글수).isEqualTo(1);
	}

	@Test
	void V7을_적용하면_visa_reviews_표가_비어_있게_생긴다() throws SQLException {
		assertThat(값("SELECT count(*) FROM visa_reviews")).isEqualTo(0);
	}
}
