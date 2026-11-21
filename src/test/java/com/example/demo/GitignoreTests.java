package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

// .gitignore 패턴이 서비스 계정 키 이름을 실제로 무시하는지 git에게 직접 물어본다(파일은 만들지 않음).
// git을 실행할 수 없는 환경(git 없음, 저장소 밖)에서는 건너뛴다.
class GitignoreTests {

	@Test
	void 서비스_계정_키_파일_이름은_gitignore가_무시한다() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		// git이 없거나 저장소가 아니면 -1로 돌려받아 건너뛴다
		Assumptions.assumeTrue(checkIgnore(root, "package.json") != -1, "git을 실행할 수 없어 건너뜀");

		String[] ignored = { "my-app-firebase-adminsdk-abc12-0123456789.json", "src/main/resources/x-firebase-adminsdk-1.json",
				"fcm-service-account.json", "fcm-service-account-prod.json", "secrets/fcm-service-account.json" };
		for (String name : ignored) {
			assertThat(checkIgnore(root, name)).as(name + " 무시됨").isEqualTo(0);
		}
		assertThat(checkIgnore(root, "package.json")).as("일반 json은 무시 안 됨").isEqualTo(1);
	}

	// git check-ignore 종료 코드: 0 = 무시됨, 1 = 무시 안 됨, 그 밖(128 등)이나 실행 실패 = -1
	private static int checkIgnore(Path root, String name) throws Exception {
		Process p;
		try {
			p = new ProcessBuilder("git", "check-ignore", "-q", "--no-index", name).directory(root.toFile())
					.redirectErrorStream(true).start();
		} catch (java.io.IOException e) {
			return -1;
		}
		p.getInputStream().readAllBytes();
		if (!p.waitFor(30, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			return -1;
		}
		int code = p.exitValue();
		return code == 0 || code == 1 ? code : -1;
	}
}
