package com.example.demo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// 테스트마다 실제 PostgreSQL 컨테이너(로컬 compose와 같은 17)를 띄우고, @ServiceConnection이 접속 정보를 자동으로 주입한다.
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17");
	}

	// 리프레시 토큰용 Redis 컨테이너 (로컬 compose와 같은 이미지). name="redis"로 Redis 접속 정보에 자동 연결된다.
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379);
	}
}
