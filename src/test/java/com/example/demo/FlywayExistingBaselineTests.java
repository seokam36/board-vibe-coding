package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 운영·로컬 DB가 S6-c 이후 있는 상태를 지키는 테스트:
// 기록장(flyway_schema_history)에 BASELINE 1행이 이미 있고, V1 파일이 있고, baseline-on-migrate 설정은 꺼진 상태.
// 이때 앱을 켜면(= Flyway migrate) 에러 없이 "할 일 없음"으로 정상 시작해야 한다.
//
// FlywayNoHistoryRejectTests와 컨테이너를 공유하지 않도록 별도 클래스·별도 컨테이너로 둔다.
class FlywayExistingBaselineTests {

	static PostgreSQLContainer postgres;

	@BeforeAll
	static void startContainer() {
		// 표 + 데이터가 있는 DB를 만든 뒤, 운영과 같이 baseline()으로 기록장에 BASELINE 1행을 남긴다.
		postgres = new PostgreSQLContainer("postgres:17")
				.withInitScripts("db/migration/V1__init.sql", "db/legacy-data.sql");
		postgres.start();

		// baselineVersion 기본값은 1
		Flyway.configure()
				.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration")
				.load()
				.baseline();
	}

	@AfterAll
	static void stopContainer() {
		postgres.stop();
	}

	// Spring Boot 기본값과 같은 조건 (baselineOnMigrate는 기본 false라 쓰지 않는다)
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
	void 기록장에_BASELINE이_있는_운영_상태는_migrate가_에러없이_할일없이_끝나고_데이터가_유지된다() throws SQLException {
		MigrateResult result = newFlyway().migrate();

		assertThat(result.migrationsExecuted).as("새로 실행된 마이그레이션 수").isZero();

		assertThat(count("SELECT count(*) FROM flyway_schema_history")).as("기록장 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND type = 'BASELINE'"))
				.as("version 1 BASELINE 행 수").isEqualTo(1);

		assertThat(count("SELECT count(*) FROM users")).as("users 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM posts")).as("posts 행 수").isEqualTo(1);

		assertThatCode(() -> newFlyway().validate()).as("validate 예외 없음").doesNotThrowAnyException();
	}
}
