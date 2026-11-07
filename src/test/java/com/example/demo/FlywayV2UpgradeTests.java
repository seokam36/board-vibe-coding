package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

// 완료 조건 1 (S7-a): Flyway로 V1까지 적용된 DB에 글이 이미 있을 때 V2를 적용해도
// 글 수가 그대로이고, 기존 글은 school_id NULL + category CHAT이며, schools에 5행이 생기는지 확인한다.
class FlywayV2UpgradeTests {

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
		// 1) V1까지만 적용
		flyway("1").migrate();
		// 2) 기존 글 3개 넣기 (V2 이전 모양이라 school/category 칸이 없다)
		run("INSERT INTO posts (content, created_at, title) VALUES ('c1', now(), 't1'), ('c2', now(), 't2'), ('c3', now(), 't3')");
		// 3) V2 적용
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
	void V2를_적용해도_기존_글_수가_그대로이고_school은_NULL_category는_CHAT이다() throws SQLException {
		assertThat(count("SELECT count(*) FROM posts")).as("글 수").isEqualTo(3);
		assertThat(count("SELECT count(*) FROM posts WHERE school_id IS NULL AND category = 'CHAT'"))
				.as("school NULL + CHAT 글 수").isEqualTo(3);
	}

	@Test
	void V2를_적용하면_schools에_다섯_학교가_들어간다() throws SQLException {
		assertThat(count("SELECT count(*) FROM schools")).isEqualTo(5);
		assertThat(count("SELECT count(*) FROM schools WHERE name IN ('경희대학교','고려대학교','성균관대학교','연세대학교','한양대학교')"))
				.as("지정한 다섯 학교 이름").isEqualTo(5);
	}

	// V2가 건 허용 값 검사(CHECK)와 NOT NULL이 실제로 막는지 (DB 방어선)
	@Test
	void 잘못된_category는_DB가_거절한다() {
		assertThatThrownBy(() -> run("INSERT INTO posts (content, created_at, title, category) VALUES ('c', now(), 't', 'XXX')"))
				.isInstanceOf(SQLException.class);
		// category에 NULL을 직접 넣으면 거절 (칸을 빼는 것과 다르다: 빼면 기본값 CHAT)
		assertThatThrownBy(() -> run("INSERT INTO posts (content, created_at, title, category) VALUES ('c', now(), 't', NULL)"))
				.isInstanceOf(SQLException.class);
	}

	// 옛 코드(category를 안 넣는 INSERT)로 글을 써도 실패하지 않고 CHAT, 학교 NULL로 저장된다
	@Test
	void category를_빼고_INSERT하면_CHAT으로_저장되고_학교는_NULL이다() throws SQLException {
		run("INSERT INTO posts (content, created_at, title) VALUES ('old', now(), 'old-title')");
		try {
			assertThat(count("SELECT count(*) FROM posts WHERE title = 'old-title' AND category = 'CHAT' AND school_id IS NULL"))
					.isEqualTo(1);
		} finally {
			// 다른 테스트의 글 수(3개) 검사에 영향 주지 않게 지운다
			run("DELETE FROM posts WHERE title = 'old-title'");
		}
	}
}
