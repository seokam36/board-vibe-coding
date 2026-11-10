package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.example.demo.visa.domain.VisaMilestone;
import com.example.demo.visa.domain.VisaPlanStage;

// S12-b 단위 테스트(스프링 없음): 마일스톤-단계 연결(필드 기준), 단계 경계, 숫자 리터럴 검사기 자체 검증.
class VisaMilestoneTests {

	// 마일스톤을 추가했는데 그걸 가리키는 단계를 안 만들면 띠에서 그 시점이 빠진다. 필드 기준으로 미리 잡는다.
	@Test
	void 모든_VisaMilestone을_가리키는_VisaPlanStage가_있다() {
		List<VisaMilestone> 가리키는_것 = Arrays.stream(VisaPlanStage.values()).map(VisaPlanStage::getMilestone).toList();
		for (VisaMilestone m : VisaMilestone.values()) {
			assertThat(가리키는_것).as("마일스톤 %s 를 가리키는 단계", m.name()).contains(m);
		}
	}

	@Test
	void 마일스톤이_없는_단계는_EXPIRED_하나뿐이다() {
		List<String> 마일스톤_없는_단계 = Arrays.stream(VisaPlanStage.values())
				.filter(s -> s.getMilestone() == null).map(Enum::name).toList();
		assertThat(마일스톤_없는_단계).containsExactly("EXPIRED");
	}

	// 숫자를 정답표에 직접 적어 경계를 검사한다(테스트는 리터럴을 써도 된다).
	@Test
	void of는_경계_값마다_기대_단계를_돌려준다() {
		assertThat(VisaPlanStage.of(-1)).isEqualTo(VisaPlanStage.EXPIRED);
		assertThat(VisaPlanStage.of(0)).isEqualTo(VisaPlanStage.URGENT);
		assertThat(VisaPlanStage.of(7)).isEqualTo(VisaPlanStage.URGENT);
		assertThat(VisaPlanStage.of(8)).isEqualTo(VisaPlanStage.CHECK);
		assertThat(VisaPlanStage.of(30)).isEqualTo(VisaPlanStage.CHECK);
		assertThat(VisaPlanStage.of(31)).isEqualTo(VisaPlanStage.PREPARE);
		assertThat(VisaPlanStage.of(120)).isEqualTo(VisaPlanStage.PREPARE);
		assertThat(VisaPlanStage.of(121)).isNull();
		assertThat(VisaPlanStage.of(100000)).isNull();
		assertThat(VisaPlanStage.of(-100000)).isEqualTo(VisaPlanStage.EXPIRED);
	}

	@Test
	void 마일스톤_숫자는_120_30_7이다() {
		assertThat(Arrays.stream(VisaMilestone.values()).map(VisaMilestone::getDaysBefore).toList())
				.containsExactly(120, 30, 7);
	}

	// ---------- 리터럴 검사기가 정말 잡아내는지 ----------

	private static final Pattern 숫자 = Pattern.compile("(?<![A-Za-z0-9_.])(120|30|7)[LlFfDd]?(?![A-Za-z0-9_.])");

	// 한 줄에서 주석·문자열을 빼고 120/30/7 리터럴이 남는지 본다(테스트 본체와 같은 규칙)
	static boolean 리터럴이_있다(String line) {
		String t = line.trim();
		if (t.startsWith("*") || t.startsWith("/*")) {
			return false; // 블록 주석 줄
		}
		String code = line.replaceAll("//.*", "").replaceAll("\"[^\"]*\"", "\"\"");
		return 숫자.matcher(code).find();
	}

	@Test
	void 검사기는_코드에_넣은_숫자를_잡는다() {
		assertThat(리터럴이_있다("if (daysLeft <= 30) {")).isTrue();
		assertThat(리터럴이_있다("return x.minusDays(120);")).isTrue();
		assertThat(리터럴이_있다("int d = 7;")).isTrue();
		assertThat(리터럴이_있다("long d = 120L;")).isTrue();
		assertThat(리터럴이_있다("foo(1, 7)")).isTrue();
		assertThat(리터럴이_있다("x == -7")).isTrue();
	}

	@Test
	void 검사기는_주석_문자열_다른_숫자는_무시한다() {
		assertThat(리터럴이_있다("int a = 1; // 30일 전")).isFalse();
		assertThat(리터럴이_있다("String s = \"PREPARE\";")).isFalse();
		assertThat(리터럴이_있다("int a = 1300;")).isFalse();
		assertThat(리터럴이_있다("int a = 17;")).isFalse();
		assertThat(리터럴이_있다("double a = 0.7;")).isFalse();
		assertThat(리터럴이_있다("int CHECK = 1;")).isFalse();
	}

	@Test
	void main_visa_코드에서_VisaMilestone_밖에는_120_30_7_리터럴이_없다() throws IOException {
		Path dir = Path.of("src/main/java/com/example/demo/visa");
		List<Path> 파일;
		try (var s = Files.walk(dir)) {
			파일 = s.filter(p -> p.toString().endsWith(".java")).toList();
		}
		// 경로가 틀려 아무것도 안 읽고 통과하는 일을 막는다
		assertThat(파일.stream().map(p -> p.getFileName().toString()))
				.contains("VisaMilestone.java", "VisaPlanStage.java", "VisaPlanResponse.java", "VisaPlanService.java");
		List<String> 위반 = new ArrayList<>();
		for (Path p : 파일) {
			if (p.getFileName().toString().equals("VisaMilestone.java")) {
				continue;
			}
			for (String line : Files.readAllLines(p)) {
				if (리터럴이_있다(line)) {
					위반.add(p.getFileName() + ": " + line.trim());
				}
			}
		}
		assertThat(위반).as("VisaMilestone 밖의 시점 숫자").isEmpty();
	}
}
