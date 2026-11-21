package com.example.demo.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.push.service.NoopPushSender;
import com.example.demo.push.service.PushSender;

// S24-b: 앱 전체를 띄웠을 때(테스트 설정 app.push.mode=off) 푸시 발송기가 꺼진 Noop인지 본다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PushSenderWiringTests {

	@Autowired
	PushSender pushSender;

	@Test
	void 테스트_설정에서는_앱이_뜨고_발송기는_꺼진_Noop이다() {
		assertThat(pushSender).isInstanceOf(NoopPushSender.class);
		assertThat(pushSender.enabled()).isFalse();
	}
}
