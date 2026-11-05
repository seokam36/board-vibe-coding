package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// S6-b-1: 빈 테스트 DB에서 Flyway가 V1을 한 번 성공적으로 실행했는지 확인한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void V1_마이그레이션이_성공_기록으로_정확히_한_번_남는다() {
		// flyway_schema_history = Flyway의 "어디까지 실행했나" 장부
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true", Integer.class);
		assertThat(count).isEqualTo(1);
	}
}
