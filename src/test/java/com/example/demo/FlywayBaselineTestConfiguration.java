package com.example.demo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Baseline 테스트 전용 설정. 공용 TestcontainersConfiguration과 섞이지 않도록 따로 둔다.
@TestConfiguration(proxyBeanMethods = false)
public class FlywayBaselineTestConfiguration {

	// 시작할 때 V1__init.sql(표 만들기) + baseline-data.sql(데이터)을 순서대로 실행해 "표는 있고 Flyway 기록장은 없는" DB를 만든다.
	@Bean
	@ServiceConnection
	PostgreSQLContainer baselinePostgresContainer() {
		return new PostgreSQLContainer("postgres:17").withInitScripts("db/migration/V1__init.sql", "db/baseline-data.sql");
	}

	// 앱 시작에 필요한 Redis (공용 설정과 같은 방식)
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> baselineRedisContainer() {
		return new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379);
	}
}
