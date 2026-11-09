package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.NestedExceptionUtils;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;

// 전체 컨텍스트로 MAIL_MODE=abc를 주면 Spring의 "빈 없음" 오류가 아니라 우리 안내 메시지로 시작이 실패해야 한다
// (명령행 인자로 줘야 application.properties보다 우선한다)
class MailModeInvalidStartupTests {

	@Test
	void mode가_abc면_전체_컨텍스트에서도_우리_메시지로_시작_실패한다() {
		assertThatThrownBy(() -> new SpringApplicationBuilder(DemoApplication.class, TestcontainersConfiguration.class)
				.run("--app.mail.mode=abc", "--server.port=0").close())
				.satisfies(t -> assertThat(NestedExceptionUtils.getMostSpecificCause(t).getMessage()).contains("MAIL_MODE는 log 또는 smtp"));
	}
}
