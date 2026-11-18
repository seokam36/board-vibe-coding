package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.example.demo.photo.service.PhotoDeleteQueue;

// S21-e: 같은 값이 한 곳에만 있는지 소스 문구로 검사한다 (동작 변화 없음)
class PermitSingleSourceTests {

	private static final Path 소스 = Path.of("src/main/java");

	private List<String> 줄들(String 찾을것) throws IOException {
		try (Stream<Path> 파일들 = Files.walk(소스)) {
			return 파일들.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
				try {
					return Files.readAllLines(p).stream().filter(l -> l.contains(찾을것)).map(l -> p.getFileName() + ": " + l.trim());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}).toList();
		}
	}

	@Test
	void 상수값은_permits_슬래시다() {
		assertThat(PhotoDeleteQueue.PERMIT_KEY_PREFIX).isEqualTo("permits/");
	}

	@Test
	void 메인코드에서_permits_문자열_리터럴은_상수_정의_한_줄에만_있다() throws IOException {
		List<String> 리터럴줄 = 줄들("\"permits/").stream().toList();
		assertThat(리터럴줄).hasSize(1);
		assertThat(리터럴줄.get(0)).contains("PERMIT_KEY_PREFIX");
	}

	@Test
	void 메인코드에_P14D_기본값이_없다() throws IOException {
		assertThat(줄들("P14D")).isEmpty();
	}
}
