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
// 이때 앱을 켜면(= Flyway migrate) 에러 없이 V2 이후의 V 파일들만 새로 적용되고 기존 데이터가 유지되어야 한다.
// (새 V 파일이 생길 때마다 아래 테스트의 "새로 적용된 수"와 "기록장 행 수"를 하나씩 올린다)
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

	// 기록장에 BASELINE 1행만 있는 운영과 같은 상태에서 migrate하면
	// V2(S7-a)·V3(S8-a)·V4(S9-a)·V5(S9-b)·V6(S10-a)·V7(S11-a)·V8(S12-a)·V9(S13-a)가 성공으로 8행 추가되고, 기존 데이터는 그대로 유지되며 V2의 채우기 규칙이 적용된다.
	// (처음엔 "할 일 없음"을 확인했고 V 파일이 늘 때마다 기대값을 올려 왔다. 운영 상태 재현이라는 의도는 그대로다.)
	@Test
	void 기록장에_BASELINE이_있는_운영_상태는_migrate하면_V2부터_V9_여덟_개가_성공으로_추가되고_데이터가_유지된다() throws SQLException {
		MigrateResult result = newFlyway().migrate();

		// V2~V9 여덟 개가 새로 실행되어야 한다 (V1은 BASELINE으로 인정되어 다시 실행되지 않는다)
		assertThat(result.migrationsExecuted).as("새로 실행된 마이그레이션 수").isEqualTo(8);

		// 기록장: BASELINE 1행 + V2~V9 성공 8행 = 9행
		assertThat(count("SELECT count(*) FROM flyway_schema_history")).as("기록장 행 수").isEqualTo(9);
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND type = 'SQL' AND success = true"))
				.as("version 3 성공 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND type = 'BASELINE'"))
				.as("version 1 BASELINE 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND type = 'SQL' AND success = true"))
				.as("version 2 성공 행 수").isEqualTo(1);

		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND type = 'SQL' AND success = true"))
				.as("version 4 성공 행 수").isEqualTo(1);
		// V5(신고 표 추가)도 성공 행으로 직접 확인
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND type = 'SQL' AND success = true"))
				.as("version 5 성공 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'reports'"))
				.as("reports 표 수").isEqualTo(1);

		// 기존 데이터 유지
		assertThat(count("SELECT count(*) FROM users")).as("users 행 수").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM posts")).as("posts 행 수").isEqualTo(1);

		// V2 결과: 학교 5행, 기존 글은 school_id NULL + category CHAT
		assertThat(count("SELECT count(*) FROM schools")).as("schools 행 수").isEqualTo(5);
		assertThat(count("SELECT count(*) FROM posts WHERE school_id IS NULL AND category = 'CHAT'"))
				.as("기존 글(school NULL, CHAT) 수").isEqualTo(1);

		assertThatCode(() -> newFlyway().validate()).as("validate 예외 없음").doesNotThrowAnyException();
	}
}
