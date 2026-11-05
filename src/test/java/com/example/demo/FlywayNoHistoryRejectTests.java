package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

// S6-c 후속: application.properties에서 baseline-on-migrate를 껐으므로,
// "표와 데이터는 있는데 Flyway 기록장(flyway_schema_history)이 없는 DB"는 이제 거절되어야 한다.
// (켜 두면 표가 일부만 있는 이상한 DB도 "V1 적용됨"으로 잘못 인정해 버리기 때문)
//
// 왜 Spring 컨텍스트(@SpringBootTest) 없이 Flyway를 직접 쓰나:
// 거절되면 앱 자체가 시작하지 못해 컨텍스트가 아예 안 뜬다. 그래서 컨테이너를 직접 띄우고 Flyway만 따로 돌려 본다.
//
// Spring Boot 자동 설정과 같은 조건인지 근거:
// - baselineOnMigrate: Boot(FlywayProperties)도 Flyway 기본값도 false. properties에서 true를 지웠으니 둘 다 false로 같다.
// - locations: Boot 기본값이 classpath:db/migration 이고, 아래에도 같은 값을 명시한다.
// - 그 밖의 설정(schemas, table 등)은 properties에 없으므로 둘 다 기본값(public, flyway_schema_history)이다.
class FlywayNoHistoryRejectTests {

	// 시작할 때 V1__init.sql(표 만들기) + legacy-data.sql(데이터)을 순서대로 실행해 "표는 있고 기록장은 없는" DB를 만든다.
	static PostgreSQLContainer postgres;

	@BeforeAll
	static void startContainer() {
		postgres = new PostgreSQLContainer("postgres:17")
				.withInitScripts("db/migration/V1__init.sql", "db/legacy-data.sql");
		postgres.start();
	}

	@AfterAll
	static void stopContainer() {
		postgres.stop();
	}

	// Spring 설정과 같은 조건의 Flyway (baselineOnMigrate는 기본값 false라서 일부러 쓰지 않는다)
	private Flyway newFlyway() {
		return Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.load();
	}

	private int count(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	@Test
	void 기록장_없는_DB는_migrate가_거절되고_데이터와_기록장은_건드리지_않는다() throws SQLException {
		// 1) 거절: FlywayException이 나야 한다.
		// 메시지 문구는 하나만 확인한다. Flyway를 올려 문구가 바뀌면 여기만 고치면 된다.
		// 핵심 확인은 예외·기록장 미생성·데이터 유지다.
		assertThatThrownBy(() -> newFlyway().migrate())
				.isInstanceOf(FlywayException.class)
				.hasMessageContaining("no schema history table");

		// 2) 거절 뒤에도 기존 회원·글 데이터가 그대로다
		assertThat(count("SELECT count(*) FROM users")).as("users 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM posts")).as("posts 행 수").isEqualTo(1);

		// 3) 기록장 표가 새로 생기지 않았다 (거절은 아무것도 바꾸지 않고 끝난다)
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'flyway_schema_history'"))
				.as("flyway_schema_history 표 개수").isZero();
	}
}
