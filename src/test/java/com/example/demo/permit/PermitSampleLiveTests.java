package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.example.demo.permit.service.PermitJudge;
import com.example.demo.permit.service.PermitJudgement;
import com.example.demo.permit.service.VisionPermitTextReader;
import com.example.demo.school.domain.School;
import com.example.demo.school.domain.SchoolNameAlias;
import com.example.demo.school.repository.SchoolNameAliasRepository;

// S20-h 실물 시험 도구: 저장소 밖 폴더(PERMIT_SAMPLE_DIR)의 허가서 견본 사진을 진짜 Vision으로 읽고 판정 규칙에 넣어 본다.
//
// 실행 방법: 환경변수 3개를 모두 정해야 돈다(없으면 건너뜀. 평소·CI에선 안 돈다).
//   VISION_LIVE_TEST=true / VISION_API_KEY=(키, 출력하지 않음) / PERMIT_SAMPLE_DIR=(이 프로젝트 폴더 밖의 사진 폴더)
//   예) ./gradlew test --tests '*PermitSampleLiveTests'
// 끝나면 반드시: PERMIT_SAMPLE_DIR/out/ 폴더와 build/test-results, build/reports를 지운다. 실물 사진은 저장소에 넣지 않는다.
//
// 개인정보 규칙: 화면(System.out = Gradle 결과 파일에 남음)에는 사진 순번·판정 코드·줄 수·후보 개수·번호 길이만 찍는다.
// 숫자를 #으로 가린 글자, 조회한 학교 후보 이름, 원래 파일 이름, 전체 글자는 PERMIT_SAMPLE_DIR/out/ 파일에만 쓴다.
// 학교 별칭 표는 DB 없이 흉내 낸다: V14 파일의 별칭 INSERT를 읽어, 실제 앱처럼 별칭에 "정확히 일치"할 때만 있다고 대답한다.
@EnabledIfEnvironmentVariable(named = "VISION_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "VISION_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PERMIT_SAMPLE_DIR", matches = ".+")
class PermitSampleLiveTests {

	private static final Path ALIAS_SQL = Path.of("src/main/resources/db/migration/V14__add_permit_requests.sql");
	// ('학교명', '별칭') 모양 한 쌍
	private static final Pattern ALIAS_PAIR = Pattern.compile("\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)");

	@Test
	void 견본_사진을_진짜_Vision으로_읽고_판정해_본다() throws Exception {
		Path project = Path.of("").toAbsolutePath().normalize();
		Path dir = Path.of(System.getenv("PERMIT_SAMPLE_DIR")).toAbsolutePath().normalize();
		// 사진 폴더가 프로젝트 안이면 out/ 파일이 커밋될 수 있으므로 바로 실패시킨다
		assertThat(dir.startsWith(project)).as("PERMIT_SAMPLE_DIR은 프로젝트 폴더 밖이어야 한다").isFalse();
		Path out = dir.resolve("out");
		Files.createDirectories(out);

		// V14의 별칭 INSERT를 읽어 별칭 -> 학교명 표를 만든다
		Map<String, String> schoolByAlias = loadAliases();
		assertThat(schoolByAlias).as("V14에서 읽은 별칭").isNotEmpty();
		// 학교명마다 일관된 가짜 id를 가진 흉내 객체를 미리 만든다(답변 도중에 만들면 Mockito가 꼬인다)
		Map<String, SchoolNameAlias> aliasBySchool = new HashMap<>();
		for (String schoolName : schoolByAlias.values()) {
			if (!aliasBySchool.containsKey(schoolName)) {
				School school = mock(School.class);
				when(school.getId()).thenReturn((long) (aliasBySchool.size() + 1));
				SchoolNameAlias alias = mock(SchoolNameAlias.class);
				when(alias.getSchool()).thenReturn(school);
				aliasBySchool.put(schoolName, alias);
			}
		}

		// 판정기가 조회한 학교 이름을 모은다(화면에는 찍지 않고 out/ 파일에만 쓴다)
		List<String> askedSchools = new ArrayList<>();
		SchoolNameAliasRepository repo = mock(SchoolNameAliasRepository.class);
		when(repo.findByAlias(anyString())).thenAnswer(inv -> {
			String name = inv.getArgument(0);
			askedSchools.add(name);
			String schoolName = schoolByAlias.get(name); // 정확히 일치할 때만 있다
			return schoolName == null ? Optional.empty() : Optional.of(aliasBySchool.get(schoolName));
		});
		PermitJudge judge = new PermitJudge(repo);

		VisionPermitTextReader reader = new VisionPermitTextReader("https://vision.googleapis.com",
				System.getenv("VISION_API_KEY"), Duration.ofSeconds(20), 2, 7L * 1024 * 1024);
		try (Stream<Path> files = Files.list(dir)) {
			int index = 0;
			for (Path photo : files.filter(PermitSampleLiveTests::isImage).sorted().toList()) {
				index++;
				String type = photo.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
				Optional<String> text = reader.read(Files.readAllBytes(photo), type);
				if (text.isEmpty()) {
					System.out.println("사진 " + index + ": 못 읽음(UNREADABLE)");
					continue;
				}
				askedSchools.clear();
				PermitJudgement result = judge.judge(text.get());
				long lineCount = text.get().lines().count();

				// 화면: 개인정보 없는 것만
				System.out.println("사진 " + index + ": 판정 " + result.result() + " / 읽은 줄 " + lineCount + "줄 / 학교 후보 "
						+ askedSchools.size() + "개 / 번호 "
						+ (result.normalizedNumber() != null ? "뽑힘(" + result.normalizedNumber().length() + "자)" : "안 뽑힘"));

				// 파일: 전체 글자, 그리고 파일 이름·학교 후보·가린 글자를 담은 보고서
				Files.writeString(out.resolve(index + ".txt"), text.get(), StandardCharsets.UTF_8);
				StringBuilder report = new StringBuilder();
				report.append("원래 파일: ").append(photo.getFileName()).append('\n');
				report.append("판정: ").append(result.result()).append('\n');
				report.append("조회한 학교 후보: ").append(askedSchools).append('\n');
				report.append("--- 읽은 글자(숫자는 #)\n");
				text.get().lines().forEach(line -> report.append("| ").append(line.replaceAll("\\d", "#")).append('\n'));
				Files.writeString(out.resolve(index + ".report.txt"), report.toString(), StandardCharsets.UTF_8);
			}
		} finally {
			reader.close();
		}
	}

	// V14의 "INSERT INTO school_name_aliases" 뒤쪽에서 ('학교명', '별칭') 쌍을 모은다
	private static Map<String, String> loadAliases() throws Exception {
		String sql = Files.readString(ALIAS_SQL, StandardCharsets.UTF_8);
		int start = sql.indexOf("INSERT INTO school_name_aliases");
		assertThat(start).as("별칭 INSERT 위치").isGreaterThanOrEqualTo(0);
		Map<String, String> schoolByAlias = new HashMap<>();
		Matcher m = ALIAS_PAIR.matcher(sql.substring(start));
		while (m.find()) {
			schoolByAlias.put(m.group(2), m.group(1)); // 별칭 -> 학교명
		}
		return schoolByAlias;
	}

	private static boolean isImage(Path p) {
		String name = p.getFileName().toString().toLowerCase();
		return Files.isRegularFile(p) && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"));
	}
}
