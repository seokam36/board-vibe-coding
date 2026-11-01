package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class) // 테스트용 PostgreSQL 컨테이너를 사용하도록 설정을 가져온다
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
