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

// 완료 조건 (S9-a-1): V3까지 적용되고 회원이 미리 있는 DB에 V4를 적용하면
// users.role 칸이 생기고, 미리 있던 회원은 전부 'USER'이며, CHECK 제약이 이상한 값을 막는지 확인한다.
// 주의: 다른 테스트와 순서가 얽히지 않도록, 전체 개수가 아니라 "내가 넣은 행(prefix 'old_')"만 본다.
class FlywayV4UpgradeTests {

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
		// 1) V3까지만 적용 (role 칸이 없는 상태)
		flyway("3").migrate();
		// 2) 기존 회원 2명 넣기 (role 칸이 아직 없다)
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'old_a', 'old_a'), (now(), 'x', 'old_b', 'old_b')");
		// 3) V4 적용
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
	void V4를_적용하면_users에_role_칸이_생긴다() throws SQLException {
		assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'role'"))
				.as("role 칸 수").isEqualTo(1);
	}

	@Test
	void V4_적용_전에_있던_회원은_전부_USER가_된다() throws SQLException {
		assertThat(count("SELECT count(*) FROM users WHERE username LIKE 'old\\_%'")).as("미리 넣은 회원 수").isEqualTo(2);
		assertThat(count("SELECT count(*) FROM users WHERE username LIKE 'old\\_%' AND role = 'USER'"))
				.as("USER인 기존 회원 수").isEqualTo(2);
	}

	@Test
	void role_없이_넣은_새_회원은_기본값_USER다() throws SQLException {
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'new_default', 'new_default')");
		assertThat(count("SELECT count(*) FROM users WHERE username = 'new_default' AND role = 'USER'")).isEqualTo(1);
	}

	@Test
	void ADMIN으로_바꾸는_것은_허용된다() throws SQLException {
		run("INSERT INTO users (created_at, password, username, nickname) VALUES (now(), 'x', 'new_admin', 'new_admin')");
		run("UPDATE users SET role = 'ADMIN' WHERE username = 'new_admin'");
		assertThat(count("SELECT count(*) FROM users WHERE username = 'new_admin' AND role = 'ADMIN'")).isEqualTo(1);
	}

	@Test
	void USER와_ADMIN_외의_값은_ck_users_role이_거부한다() {
		assertThatThrownBy(() -> run("INSERT INTO users (created_at, password, username, nickname, role) VALUES (now(), 'x', 'bad_role', 'bad_role', 'SUPER')"))
				.hasMessageContaining("ck_users_role");
		assertThatThrownBy(() -> run("UPDATE users SET role = 'root' WHERE username = 'old_a'"))
				.hasMessageContaining("ck_users_role");
	}

	@Test
	void role은_NULL이_될_수_없다() {
		assertThatThrownBy(() -> run("UPDATE users SET role = NULL WHERE username = 'old_b'"))
				.hasMessageContaining("null");
	}

	@Test
	void V4가_성공_기록으로_한_번_남는다() throws SQLException {
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success = true")).isEqualTo(1);
	}
}
