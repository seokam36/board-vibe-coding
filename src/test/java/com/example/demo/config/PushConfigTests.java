package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.demo.push.service.FcmPushSender;
import com.example.demo.push.service.NoopPushSender;
import com.example.demo.push.service.PushSender;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

// PUSH_MODE에 따라 어떤 발송기가 등록되는지, 이상한 설정이면 시작이 실패하는지 검사한다 (DB 없이 PushConfig만 띄움. PermitOcrConfigTests와 같은 방식).
// 서비스 계정 키 파일은 테스트 안에서 지어낸 RSA 키로 임시 폴더에 만든다(저장소에 키 파일이 없다). 구글은 부르지 않는다.
class PushConfigTests {

	private static final String PRIVATE_MARK = "FAKE-PRIVATE-KEY-ID-98765";

	@TempDir
	Path dir;

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PushConfig.class);

	// 진짜처럼 파싱되는 서비스 계정 JSON을 임시 폴더에 쓰고 경로를 돌려준다(키·ID는 전부 지어낸 값)
	private Path writeKeyFile(String name, Map<String, Object> overrides) throws Exception {
		KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
		gen.initialize(2048);
		KeyPair pair = gen.generateKeyPair();
		String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pair.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
		Map<String, Object> json = new java.util.LinkedHashMap<>();
		json.put("type", "service_account");
		json.put("project_id", "demo-test-project");
		json.put("private_key_id", PRIVATE_MARK);
		json.put("private_key", pem);
		json.put("client_email", "test@demo-test-project.example.invalid");
		json.put("client_id", "123456789");
		json.put("token_uri", "http://127.0.0.1:1/token");
		json.putAll(overrides);
		Path file = dir.resolve(name);
		Files.writeString(file, new ObjectMapper().writeValueAsString(json));
		return file;
	}

	private ApplicationContextRunner fcm(Path file, String... more) {
		return runner.withPropertyValues("app.push.mode=fcm", "app.push.fcm.credentials-file=" + file.toString().replace("\\", "/"))
				.withPropertyValues(more);
	}

	private static String all(Throwable t) {
		StringBuilder sb = new StringBuilder(t.toString());
		for (Throwable c = t; c != null; c = c.getCause()) {
			sb.append(' ').append(c.getMessage());
		}
		return sb.toString();
	}

	@Test
	void 설정이_없으면_off이고_Noop_발송기가_등록된다() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			PushSender sender = ctx.getBean(PushSender.class);
			assertThat(sender).isInstanceOf(NoopPushSender.class);
			assertThat(sender.enabled()).isFalse();
		});
	}

	@Test
	void fcm_모드면_Fcm_발송기만_등록되고_구글에_닿지_않아도_시작은_성공한다() throws Exception {
		fcm(writeKeyFile("ok.json", Map.of())).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			PushSender sender = ctx.getBean(PushSender.class);
			assertThat(sender).isInstanceOf(FcmPushSender.class);
			assertThat(sender.enabled()).isTrue();
			assertThat(ctx).doesNotHaveBean(NoopPushSender.class);
		});
	}

	@Test
	void 모르는_모드면_시작이_실패하고_입력값은_출력하지_않는다() {
		for (String bad : new String[] { "abc", "FCM", "Off", "" }) {
			runner.withPropertyValues("app.push.mode=" + bad).run(ctx -> {
				assertThat(ctx).as(bad).hasFailed();
				assertThat(all(ctx.getStartupFailure())).contains("PUSH_MODE").contains("off").contains("fcm");
			});
		}
		runner.withPropertyValues("app.push.mode=secretmode987").run(ctx ->
				assertThat(all(ctx.getStartupFailure())).doesNotContain("secretmode987"));
	}

	@Test
	void fcm_모드인데_키_파일_경로가_비면_변수_이름을_알려_주며_시작이_실패한다() {
		for (String empty : new String[] { "", "   " }) {
			runner.withPropertyValues("app.push.mode=fcm", "app.push.fcm.credentials-file=" + empty).run(ctx -> {
				assertThat(ctx).hasFailed();
				assertThat(all(ctx.getStartupFailure())).contains("FCM_CREDENTIALS_FILE");
			});
		}
		runner.withPropertyValues("app.push.mode=fcm").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(all(ctx.getStartupFailure())).contains("FCM_CREDENTIALS_FILE");
		});
	}

	@Test
	void 없는_파일이면_변수_이름을_알려_주며_시작이_실패하고_경로는_출력하지_않는다() {
		Path missing = dir.resolve("secret-folder-xyz").resolve("nope.json");
		fcm(missing).run(ctx -> {
			assertThat(ctx).hasFailed();
			String msg = all(ctx.getStartupFailure());
			assertThat(msg).contains("FCM_CREDENTIALS_FILE").doesNotContain("secret-folder-xyz");
		});
	}

	@Test
	void 서비스_계정_JSON이_아니면_시작이_실패하고_키_내용은_출력하지_않는다() throws Exception {
		Path notJson = dir.resolve("notjson.json");
		Files.writeString(notJson, "this is SECRET-CONTENT-123 not json");
		Path otherType = writeKeyFile("authorized-user.json", Map.of("type", "authorized_user"));
		Path noProject = writeKeyFile("noproject.json", Map.of("project_id", ""));
		Path badKey = writeKeyFile("badkey.json", Map.of("private_key", "not-a-pem-SECRET-PEM-BODY"));
		for (Path p : new Path[] { notJson, otherType, noProject, badKey }) {
			fcm(p).run(ctx -> {
				assertThat(ctx).as(p.getFileName().toString()).hasFailed();
				String msg = all(ctx.getStartupFailure());
				assertThat(msg).contains("FCM_CREDENTIALS_FILE").doesNotContain("SECRET-CONTENT-123").doesNotContain("SECRET-PEM-BODY")
						.doesNotContain(PRIVATE_MARK).doesNotContain("BEGIN PRIVATE KEY");
			});
		}
	}

	@Test
	void 주소는_구글_FCM과_내_컴퓨터만_허용한다() throws Exception {
		Path file = writeKeyFile("ok.json", Map.of());
		for (String ok : new String[] { "https://fcm.googleapis.com", "https://fcm.googleapis.com/", "http://localhost:8089",
				"http://127.0.0.1:1", "https://localhost" }) {
			fcm(file, "app.push.fcm.base-url=" + ok).run(ctx -> assertThat(ctx).as(ok).hasNotFailed());
		}
		for (String bad : new String[] { "http://fcm.googleapis.com", "https://evil.example.com", "https://fcm.googleapis.com.evil.com",
				"https://fcm.googleapis.com@evil.com", "https://fcm.googleapis.com:8443", "http://localhost.evil.com", "ftp://localhost", "" }) {
			fcm(file, "app.push.fcm.base-url=" + bad).run(ctx -> {
				assertThat(ctx).as(bad).hasFailed();
				assertThat(all(ctx.getStartupFailure())).contains("app.push.fcm.base-url");
			});
		}
	}

	// 흉내 주소: 호스트가 localhost·127.0.0.1로 "시작"만 하는 주소와 사용자 정보 위장
	@Test
	void 흉내_주소는_모두_시작이_실패한다() throws Exception {
		Path file = writeKeyFile("ok.json", Map.of());
		for (String bad : new String[] { "http://127.0.0.1.evil.com", "http://127.0.0.1.evil.com:80", "http://localhost@evil.com",
				"http://localhost:80@evil.com", "http://localhost.evil.com:8080", "https://fcm.googleapis.com.evil.com/", "https://evilfcm.googleapis.com",
				"https://fcm.googleapis.com:444", "http://evil.com/localhost", "http://evil.com?x=localhost" }) {
			fcm(file, "app.push.fcm.base-url=" + bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
		}
	}

	@Test
	void 제한_시간이_0_이하면_변수_이름을_알려_주며_시작이_실패한다() throws Exception {
		Path file = writeKeyFile("ok.json", Map.of());
		for (String bad : new String[] { "0", "0s", "-5s" }) {
			fcm(file, "app.push.fcm.timeout=" + bad).run(ctx -> {
				assertThat(ctx).as(bad).hasFailed();
				assertThat(all(ctx.getStartupFailure())).contains("FCM_TIMEOUT");
			});
		}
	}

	@Test
	void fcm_시작_로그는_한_줄이고_키_경로_주소가_없다() throws Exception {
		Path file = writeKeyFile("secret-dir-key.json", Map.of());
		Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(PushConfig.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			fcm(file).run(ctx -> assertThat(ctx).hasNotFailed());
		} finally {
			logger.detachAppender(appender);
		}
		assertThat(appender.list).hasSize(1);
		String line = appender.list.get(0).getFormattedMessage();
		assertThat(line).contains("푸시 fcm 켜짐").doesNotContain("secret-dir-key").doesNotContain(PRIVATE_MARK)
				.doesNotContain("demo-test-project").doesNotContain("googleapis");
	}
}
