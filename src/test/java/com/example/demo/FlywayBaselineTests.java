package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// [나중 계획 메모] S6-c 이후 application.properties에서 baseline-on-migrate를 지우면,
// 이 테스트는 "기록장 없는 DB는 앱 시작 실패"를 확인하는 테스트로 바꿔야 한다.
// 힌트: baseline-on-migrate를 지우면 @SpringBootTest로는 컨텍스트가 안 뜨므로, 컨테이너에 Flyway를 직접 붙여
// (Flyway.configure().dataSource(...)) migrate() 시 FlywayException을 assertThrows하는 방식으로 바꾼다.
//
// S6-b-2: 운영 첫 배포 경로 검증. 표와 데이터는 있고 flyway_schema_history는 없는 DB에서
// Flyway가 V1을 다시 실행하지 않고 BASELINE 기록만 남기는지, 기존 데이터가 보존되는지 확인한다.
@SpringBootTest
@Import(FlywayBaselineTestConfiguration.class)
class FlywayBaselineTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	@Test
	void 기록장_없는_DB도_앱이_정상_시작되고_BASELINE_기록이_정확히_한_행_남는다() {
		// 여기까지 왔다면 컨텍스트 시작(ddl-auto=validate 포함)이 통과한 것이다.
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT version, type, success FROM flyway_schema_history");

		assertThat(rows).as("기록장 행은 BASELINE 하나뿐이어야 한다").hasSize(1);
		Map<String, Object> row = rows.get(0);
		assertThat(row.get("version")).as("기준 버전").isEqualTo("1");
		assertThat(row.get("type")).as("V1을 실행하지 않고 BASELINE으로 기록").isEqualTo("BASELINE");
		assertThat(row.get("success")).as("성공 여부").isEqualTo(true);
	}

	@Test
	void 기존_회원과_게시글_데이터가_그대로_보존된다() {
		Integer users = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
		Integer posts = jdbcTemplate.queryForObject("SELECT count(*) FROM posts", Integer.class);
		assertThat(users).as("users 행 수").isEqualTo(1);
		assertThat(posts).as("posts 행 수").isEqualTo(1);

		Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = 'olduser'", Long.class);
		Long authorId = jdbcTemplate.queryForObject("SELECT author_id FROM posts", Long.class);
		assertThat(authorId).as("글의 작성자가 기존 회원이어야 한다").isEqualTo(userId);
	}

	@Test
	void 두_번째_시작에서는_마이그레이션이_실행되지_않고_기록장과_데이터가_그대로다() {
		// 앱 재시작 흉내: 이미 BASELINE이 기록된 DB에서 migrate()를 한 번 더 부른다.
		MigrateResult result = flyway.migrate();

		assertThat(result.migrationsExecuted).as("실행된 마이그레이션 수").isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
				.as("기록장 행 수는 여전히 1").isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class))
				.as("users 행 수 그대로").isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM posts", Integer.class))
				.as("posts 행 수 그대로").isEqualTo(1);
	}
}
