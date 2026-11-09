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

// 완료 조건 (S10-a 1·2): V5까지 적용되고 회원·글이 미리 있는 DB에 V6를 적용하면
// 기존 데이터가 유지되고, 새 3칸은 NULL이며, 도메인 6개가 맞는 학교에 들어가고, UNIQUE 제약이 동작하는지 확인한다.
// 주의: 내가 넣은 행(prefix 'old_')만 본다.
class FlywayV6UpgradeTests {

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
		// 1) V5까지만 적용
		flyway("5").migrate();
		// 2) 기존 회원 2명과 글 1개
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'old_a', 'old_a')");
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'old_b', 'old_b')");
		long userId = 값("SELECT id FROM users WHERE username = 'old_a'");
		run("INSERT INTO posts (title, content, created_at, author_id, category) VALUES ('old_post', 'c', now(), " + userId + ", 'CHAT')");
		// 3) V6 적용
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

	private static long 학교id(String name) throws SQLException {
		return 값("SELECT id FROM schools WHERE name = '" + name + "'");
	}

	// 완료 조건 1
	@Test
	void V6가_성공_기록으로_한_번_남는다() throws SQLException {
		assertThat(값("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success = true")).isEqualTo(1);
	}

	@Test
	void V6를_적용해도_기존_회원과_글이_그대로_남는다() throws SQLException {
		assertThat(값("SELECT count(*) FROM users WHERE username IN ('old_a','old_b')")).isEqualTo(2);
		assertThat(값("SELECT count(*) FROM posts WHERE title = 'old_post'")).isEqualTo(1);
	}

	@Test
	void V6를_적용하면_기존_회원의_인증_세_칸이_모두_NULL이다() throws SQLException {
		assertThat(값("SELECT count(*) FROM users WHERE username LIKE 'old_%' AND verified_school_id IS NULL "
				+ "AND verified_at IS NULL AND school_email_hash IS NULL")).isEqualTo(2);
	}

	// 완료 조건 2
	@Test
	void 도메인_6개가_맞는_학교에_연결되어_있다() throws SQLException {
		assertThat(값("SELECT count(*) FROM school_email_domains")).isEqualTo(6);
		String[][] expected = { { "경희대학교", "khu.ac.kr" }, { "고려대학교", "korea.ac.kr" }, { "성균관대학교", "skku.edu" },
				{ "성균관대학교", "g.skku.edu" }, { "연세대학교", "yonsei.ac.kr" }, { "한양대학교", "hanyang.ac.kr" } };
		for (String[] e : expected) {
			assertThat(값("SELECT count(*) FROM school_email_domains WHERE domain = '" + e[1] + "' AND school_id = "
					+ 학교id(e[0]))).as(e[1] + " -> " + e[0]).isEqualTo(1);
		}
	}

	@Test
	void 새_제약_이름이_모두_생긴다() throws SQLException {
		for (String name : new String[] { "fk_school_email_domains_school", "uk_school_email_domains_domain",
				"ck_school_email_domains_lower", "fk_users_verified_school", "uk_users_school_email_hash" }) {
			assertThat(값("SELECT count(*) FROM pg_constraint WHERE conname = '" + name + "'")).as("제약 " + name).isEqualTo(1);
		}
	}

	@Test
	void 같은_도메인을_두_번_넣으면_uk_school_email_domains_domain이_거부한다() {
		assertThatThrownBy(() -> run("INSERT INTO school_email_domains (school_id, domain) VALUES (" + 학교id("연세대학교")
				+ ", 'yonsei.ac.kr')")).hasMessageContaining("uk_school_email_domains_domain");
	}

	@Test
	void 대문자_도메인을_넣으면_ck_school_email_domains_lower가_거부한다() {
		assertThatThrownBy(() -> run("INSERT INTO school_email_domains (school_id, domain) VALUES (" + 학교id("연세대학교")
				+ ", 'Upper.ac.kr')")).hasMessageContaining("ck_school_email_domains_lower");
	}

	@Test
	void 같은_메일_해시를_두_회원에_넣으면_uk_users_school_email_hash가_거부한다() throws SQLException {
		String hash = "a".repeat(64);
		run("UPDATE users SET school_email_hash = '" + hash + "' WHERE username = 'old_a'");
		try {
			assertThatThrownBy(() -> run("UPDATE users SET school_email_hash = '" + hash + "' WHERE username = 'old_b'"))
					.hasMessageContaining("uk_users_school_email_hash");
		} finally {
			// 되돌려서 다른 테스트에 영향이 없게 한다
			run("UPDATE users SET school_email_hash = NULL WHERE username = 'old_a'");
		}
	}

	@Test
	void 메일_해시_NULL은_여러_회원이_가질_수_있다() throws SQLException {
		// 기존 회원 두 명 모두 NULL인 상태가 UNIQUE 안에서 공존한다
		assertThat(값("SELECT count(*) FROM users WHERE school_email_hash IS NULL AND username LIKE 'old_%'")).isEqualTo(2);
	}

	@Test
	void 없는_학교로_연결하면_FK가_거부한다() {
		assertThatThrownBy(() -> run("UPDATE users SET verified_school_id = 999999 WHERE username = 'old_b'"))
				.hasMessageContaining("fk_users_verified_school");
	}
}
