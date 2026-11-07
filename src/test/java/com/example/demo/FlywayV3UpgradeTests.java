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

// 완료 조건 (S8-a): V1·V2까지 적용되고 글 3개가 있는 DB에 V3를 적용해도
// 글 3개가 그대로이고, comments·post_likes 표가 새로 생기는지 확인한다.
class FlywayV3UpgradeTests {

	static PostgreSQLContainer postgres;

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
		// 1) V2까지만 적용 (V3 이전 상태)
		flyway("2").migrate();
		// 2) 기존 글 3개 넣기 (V2 이후 모양: category는 기본값 CHAT)
		run("INSERT INTO posts (content, created_at, title) VALUES ('c1', now(), 't1'), ('c2', now(), 't2'), ('c3', now(), 't3')");
		// 3) V3 적용
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

	private static int count(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	@Test
	void V3를_적용해도_기존_글_3개가_그대로다() throws SQLException {
		assertThat(count("SELECT count(*) FROM posts")).as("글 수").isEqualTo(3);
	}

	@Test
	void V3를_적용하면_comments와_post_likes_표가_생기고_비어_있다() throws SQLException {
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('comments','post_likes')"))
				.as("새 표 수").isEqualTo(2);
		assertThat(count("SELECT count(*) FROM comments")).isZero();
		assertThat(count("SELECT count(*) FROM post_likes")).isZero();
	}

	@Test
	void V3가_성공_기록으로_한_번_남는다() throws SQLException {
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success = true")).isEqualTo(1);
	}

	// ON DELETE CASCADE가 DB 수준에서 실제로 동작하는지: 글을 지우면 댓글도 같이 사라진다
	@Test
	void 글을_지우면_그_글의_댓글도_DB가_같이_지운다() throws SQLException {
		run("INSERT INTO posts (content, created_at, title) VALUES ('cc', now(), 'cascade-post')");
		run("INSERT INTO comments (post_id, content, created_at) SELECT id, 'x', now() FROM posts WHERE title = 'cascade-post'");
		assertThat(count("SELECT count(*) FROM comments")).isEqualTo(1);
		run("DELETE FROM posts WHERE title = 'cascade-post'");
		assertThat(count("SELECT count(*) FROM comments")).isZero();
		assertThat(count("SELECT count(*) FROM posts")).as("다른 글은 그대로").isEqualTo(3);
	}

	// post_likes의 ON DELETE CASCADE: 글을 지우면 그 글의 공감 행도 같이 사라진다
	@Test
	void 글을_지우면_그_글의_공감도_DB가_같이_지운다() throws SQLException {
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'lk1', 'lk1')");
		run("INSERT INTO posts (content, created_at, title) VALUES ('lc', now(), 'like-post')");
		run("INSERT INTO post_likes (post_id, user_id, created_at) SELECT p.id, u.id, now() FROM posts p, users u WHERE p.title = 'like-post' AND u.username = 'lk1'");
		assertThat(count("SELECT count(*) FROM post_likes")).isEqualTo(1);
		run("DELETE FROM posts WHERE title = 'like-post'");
		assertThat(count("SELECT count(*) FROM post_likes")).isZero();
		assertThat(count("SELECT count(*) FROM users WHERE username = 'lk1'")).as("회원은 그대로").isEqualTo(1);
	}

	// post_likes.user_id의 ON DELETE CASCADE: 회원을 지우면 그 회원의 공감 행도 같이 사라진다
	@Test
	void 회원을_지우면_그_회원의_공감도_DB가_같이_지운다() throws SQLException {
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'lk2', 'lk2')");
		run("INSERT INTO posts (content, created_at, title) VALUES ('lc', now(), 'like-post2')");
		run("INSERT INTO post_likes (post_id, user_id, created_at) SELECT p.id, u.id, now() FROM posts p, users u WHERE p.title = 'like-post2' AND u.username = 'lk2'");
		assertThat(count("SELECT count(*) FROM post_likes WHERE user_id = (SELECT id FROM users WHERE username = 'lk2')")).isEqualTo(1);
		run("DELETE FROM users WHERE username = 'lk2'");
		assertThat(count("SELECT count(*) FROM post_likes")).as("공감 행").isZero();
		assertThat(count("SELECT count(*) FROM posts WHERE title = 'like-post2'")).as("글은 그대로").isEqualTo(1);
		run("DELETE FROM posts WHERE title = 'like-post2'"); // 다른 테스트의 "글 3개" 검사를 흐트러뜨리지 않게 치운다
	}
}
