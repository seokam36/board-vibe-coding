package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.permit.domain.PermitMachineResult;
import com.example.demo.permit.service.PermitJudge;
import com.example.demo.permit.service.PermitJudgement;
import com.example.demo.permit.service.PermitNumberHash;

// S20-d1 완료 조건: 허가서 글자 판정 규칙(제목·국적·학교 별칭·번호). 번호·이름은 모두 지어낸 값이다.
// 학교 별칭 표는 V14가 넣어 둔 값(연세·고려 등, 숭실은 없음)을 그대로 쓴다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PermitJudgeTests {

	@Autowired PermitJudge judge;
	@Autowired JdbcTemplate jdbc;

	// 글자를 만들어 주는 도우미: 제목·국적·학교·번호 줄을 사용자가 원하는 대로 바꿔 끼운다
	private String 글자(String 제목, String 국적줄, String 학교줄, String 번호줄) {
		return String.join("\n", 제목, 국적줄, 학교줄, 번호줄);
	}

	private static final String 제목 = "표준입학허가서";
	private static final String 국적 = "국적(Country of citizenship): 베트남";
	private static final String 학교 = "학교명(School name): 연세대학교";
	private static final String 번호 = "표준입학허가서 번호(Certificate of Admission No.): 0000111122223333";

	private Long 학교id(String 이름) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, 이름);
	}

	@Test
	void 네_가지가_모두_맞으면_ALL_PASSED() {
		PermitJudgement r = judge.judge(글자(제목, 국적, 학교, 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("연세대학교"));
		assertThat(r.normalizedNumber()).isEqualTo("0000111122223333");
	}

	@Test
	void 영문_제목도_통과한다() {
		PermitJudgement r = judge.judge(글자("Certificate of Admission", 국적, 학교, 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 제목이_없으면_NO_TITLE() {
		// 번호 줄에도 "표준입학허가서" 글자가 들어 있으므로(규칙상 글자 어디든 있으면 제목 통과) 번호 줄 없이 시험한다
		PermitJudgement r = judge.judge("무슨 증명서\n" + 국적 + "\n" + 학교);
		assertThat(r.result()).isEqualTo(PermitMachineResult.NO_TITLE);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("연세대학교")); // 학교는 참고용으로 남는다
		assertThat(r.normalizedNumber()).isNull();
	}

	@Test
	void 글자가_null이거나_비면_NO_TITLE() {
		assertThat(judge.judge(null).result()).isEqualTo(PermitMachineResult.NO_TITLE);
		assertThat(judge.judge("  \n ").result()).isEqualTo(PermitMachineResult.NO_TITLE);
	}

	@Test
	void 한국_국적은_NATIONALITY_UNCLEAR() {
		for (String 값 : new String[] { "KOREA", "Republic of Korea", "대한민국", "대 한 민 국", "North Korea", "북한",
				"한국", "조선", "south korea", "korean", "KOR", "ROK", "R.O.K", "DPRK", "PRK", "韓國", "大韓民國", "朝鮮", "Corea" }) {
			PermitJudgement r = judge.judge(글자(제목, "국적(Country of citizenship): " + 값, 학교, 번호));
			assertThat(r.result()).as(값).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			assertThat(r.detectedSchoolId()).as(값).isEqualTo(학교id("연세대학교")); // 국적에서 떨어져도 학교는 남는다
			assertThat(r.normalizedNumber()).isNull();
		}
	}

	// ---- review 수정: 다음 줄·기호·닫히지 않은 괄호·여러 라벨 줄·번호 붙이기 ----

	private PermitJudgement 국적줄들(String... 줄들) {
		return judge.judge(제목 + "\n" + 학교 + "\n" + String.join("\n", 줄들) + "\n" + 번호);
	}

	@Test
	void 국적_다음_줄이_다른_칸_라벨이면_값이_없는_것이라_불통과() {
		for (String 라벨줄 : new String[] { "생년월일(Date of birth)", "성명(Name)", "학교주소(School address)" }) {
			PermitJudgement r = 국적줄들("국적(Country of citizenship)", 라벨줄, "대한민국", "2000-01-01");
			assertThat(r.result()).as(라벨줄).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			assertThat(r.detectedSchoolId()).isEqualTo(학교id("연세대학교"));
		}
	}

	@Test
	void 국적_값이_기호_하나면_빈_값으로_보고_다음_줄을_읽는다() {
		for (String 기호 : new String[] { "|", "-", "_", "□" }) {
			assertThat(국적줄들("국적(Country of citizenship) " + 기호, "대한민국").result()).as(기호)
					.isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			assertThat(국적줄들("국적(Country of citizenship) " + 기호, "베트남").result()).as(기호)
					.isEqualTo(PermitMachineResult.ALL_PASSED);
		}
	}

	@Test
	void 괄호가_닫히지_않거나_괄호_안에_한국_말이_있어도_불통과() {
		assertThat(국적줄들("국적(Country of citizenship 대한민국", "성명(Name): 김가짜").result())
				.isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적줄들("국적 (대한민국)").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 국적_라벨이_여러_줄이면_하나라도_한국이면_불통과이고_안내문_줄은_건너뛴다() {
		// 허용 목록 방식: 국적 라벨이 나오는 모든 줄의 값이 나라 이름이어야 하므로, 안내문 줄("은 여권과 ...")이 있으면 베트남이어도 관리자 대기
		// (예전 블랙리스트 방식에서는 ALL_PASSED였다. 값을 엉뚱하게 집어도 통과하는 구멍을 막으려고 일부러 안전한 쪽으로 바꿨다)
		assertThat(국적줄들("※ 국적은 여권과 같게 적는다", "국적(Country of citizenship): 베트남").result())
				.isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적줄들("※ 국적은 여권과 같게 적는다", "국적(Country of citizenship): 대한민국").result())
				.isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 허용_목록_방식_엉뚱한_값을_집으면_모두_불통과() {
		// 같은 줄에 다른 칸 라벨이 붙고 다음 줄이 이름 섞인 값
		assertThat(국적줄들("국적(Country of citizenship) 성명(Name)", "대한민국 홍길동").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적줄들("국적(Country of citizenship) 성명(Name)", "베트남 응우옌").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		// 라벨 묶음 뒤에 값 묶음이 오는 모양: 국적 라벨 다음 줄이 이름 값
		assertThat(국적줄들("성명(Name)", "국적(Country of citizenship)", "홍길동", "대한민국").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		// 안내문 줄 + 라벨 뒤가 다른 라벨
		assertThat(국적줄들("※ 국적은 여권과 같게 적는다", "국적(Country of citizenship)", "성명(Name)").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 허용_목록_괄호로_두_표기를_쓰면_둘_다_나라_이름이어야_통과() {
		assertThat(국적줄들("국적(Country of citizenship): 베트남(Vietnam)").result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(국적줄들("국적(Country of citizenship)", "베트남(Vietnam)").result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(국적줄들("국적(Country of citizenship): 베트남(홍길동)").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적줄들("국적(Country of citizenship): 대한민국(Vietnam)").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적줄들("국적(Country of citizenship): 베트남 (Vietnam").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 허용_목록에_한국_계열은_없다() {
		for (String 값 : new String[] { "South Korea", "North Korea", "Korea, Republic of", "Republic of Korea", "대한민국", "북한", "한국", "KR", "KP" }) {
			assertThat(국적줄들("국적(Country of citizenship): " + 값).result()).as(값).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		}
	}

	@Test
	void 번호_뒤에_날짜가_붙으면_날짜_앞까지만_번호로_쓴다() {
		String 줄 = "표준입학허가서 번호(Certificate of Admission No.): 0130000000000001 2026.03.02";
		PermitJudgement r = judge.judge(글자(제목, 국적, 학교, 줄));
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.normalizedNumber()).isEqualTo("0130000000000001");
	}

	@Test
	void 번호_칸에_날짜만_있으면_다음_줄의_숫자를_번호로_읽지_않는다() {
		String 줄 = "표준입학허가서 번호(Certificate of Admission No.): 2013-01-01\n9999888877776666";
		assertThat(judge.judge(글자(제목, 국적, 학교, 줄)).result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
		// 옆 칸 라벨뿐이라 비어 있던 경우는 다음 줄을 계속 읽는다(견본 모양)
		String 라벨만 = "표준입학허가서 번호(Certificate of Admission No.)\n9999888877776666";
		assertThat(judge.judge(글자(제목, 국적, 학교, 라벨만)).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 나라_이름_목록에_한국어와_영어_이름이_들어_있다() {
		// 운영 JRE에 한국어 로케일 데이터가 없으면 한글 값이 전부 빠지므로 시작할 때 이 검사로 경고한다
		assertThat(judge.isCountryListReady()).isTrue();
	}

	@Test
	void 번호의_띄어쓰기와_하이픈_사이_공백을_붙여서_하나로_본다() {
		String 띄어쓴번호 = "표준입학허가서 번호(Certificate of Admission No.): 0130 0000 0000 0001";
		assertThat(judge.judge(글자(제목, 국적, 학교, 띄어쓴번호)).normalizedNumber()).isEqualTo("0130000000000001");
		String 하이픈공백 = "표준입학허가서 번호: 2013 - 0001";
		PermitJudgement r = judge.judge(글자(제목, 국적, 학교, 하이픈공백));
		assertThat(r.normalizedNumber()).isEqualTo(PermitNumberHash.normalize("2013-0001")); // 관리자가 입력하는 값과 같다
	}

	@Test
	void 번호_라벨_뒤가_not_같은_문장이면_라벨이_아니다() {
		String 문장 = "This Certificate of Admission not valid after 2027-02-28";
		assertThat(judge.judge(글자(제목, 국적, 학교, 문장)).result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 번호_다음_줄이_날짜나_라벨이면_번호가_아니다() {
		String 라벨 = "표준입학허가서 번호(Certificate of Admission No.)";
		assertThat(judge.judge(글자(제목, 국적, 학교, 라벨 + "\n2027-02-28")).result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
		assertThat(judge.judge(글자(제목, 국적, 학교, 라벨 + "\n입학허가번호(Admission Number)\n9999888877776666")).result())
				.isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 국적_라벨이_없거나_값이_비면_NATIONALITY_UNCLEAR() {
		assertThat(judge.judge(글자(제목, "성별: 여성", 학교, 번호)).result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		// 라벨만 있고 뒤에 줄이 더 없으면 값이 비어 불통과
		assertThat(judge.judge(제목 + "\n국적(Country of citizenship):").result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 외국_국적과_고려대_별칭이면_국적에서_안_떨어진다() {
		for (String 국 : new String[] { "CHINA", "베트남" }) {
			for (String 교 : new String[] { "고려대학교", "Korea University" }) {
				PermitJudgement r = judge.judge(글자(제목, "국적(Country of citizenship): " + 국, "학교명(School name): " + 교, 번호));
				assertThat(r.result()).as(국 + "/" + 교).isEqualTo(PermitMachineResult.ALL_PASSED);
				assertThat(r.detectedSchoolId()).isEqualTo(학교id("고려대학교"));
			}
		}
	}

	@Test
	void 출생국가가_대한민국이어도_국적이_베트남이면_통과() {
		// 두 칸이 한 줄에 붙어 읽힌 경우(출생국가가 앞)와 줄이 나뉜 경우 모두
		String 한줄 = "출생국가(Country of birth): 대한민국 국적(Country of citizenship): 베트남";
		assertThat(judge.judge(글자(제목, 한줄, 학교, 번호)).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		String 두줄 = "출생국가(Country of birth): 대한민국\n국적(Country of citizenship): 베트남";
		assertThat(judge.judge(글자(제목, 두줄, 학교, 번호)).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		// 국적이 앞, 출생국가가 뒤에 붙은 경우도 출생국가 쪽은 값에서 잘라 낸다
		String 반대 = "국적(Country of citizenship): 베트남 출생국가(Country of birth): 대한민국";
		assertThat(judge.judge(글자(제목, 반대, 학교, 번호)).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 별칭에_없는_학교는_SCHOOL_NOT_MATCHED이고_학교_id가_null() {
		for (String 교 : new String[] { "숭실대학교", "Soongsil University" }) {
			PermitJudgement r = judge.judge(글자(제목, 국적, "학교명(School name): " + 교, 번호));
			assertThat(r.result()).as(교).isEqualTo(PermitMachineResult.SCHOOL_NOT_MATCHED);
			assertThat(r.detectedSchoolId()).isNull();
		}
	}

	@Test
	void 학교_값의_대소문자와_공백은_정리해서_비교한다() {
		PermitJudgement r = judge.judge(글자(제목, 국적, "학교명(School name):  YONSEI   University ", 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("연세대학교"));
	}

	@Test
	void 학교명_칸이_아닌_곳의_khu는_학교로_안_본다() {
		String 주소 = "학교주소(School address): 12 khu street, khu";
		PermitJudgement r = judge.judge(글자(제목, 국적, 주소, 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.SCHOOL_NOT_MATCHED);
		assertThat(r.detectedSchoolId()).isNull();
	}

	@Test
	void 학교_값에_별칭이_일부만_들어_있어도_일치로_안_본다() {
		PermitJudgement r = judge.judge(글자(제목, 국적, "학교명(School name): khu academy", 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.SCHOOL_NOT_MATCHED);
	}

	@Test
	void 번호_라벨이_없으면_NUMBER_NOT_FOUND_입학허가번호만_있어도_마찬가지() {
		assertThat(judge.judge(제목 + "\n" + 국적 + "\n" + 학교).result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
		String 다른칸 = "입학허가번호(Admission Number): 9999888877776666";
		PermitJudgement r = judge.judge(글자(제목, 국적, 학교, 다른칸));
		assertThat(r.result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("연세대학교")); // 다른 항목은 통과했으니 학교는 남는다
		assertThat(r.normalizedNumber()).isNull();
	}

	@Test
	void 번호가_4자_미만이면_NUMBER_NOT_FOUND() {
		String 짧음 = "표준입학허가서 번호(Certificate of Admission No.): 12-3";
		assertThat(judge.judge(글자(제목, 국적, 학교, 짧음)).result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 한글이_섞인_번호에서_영숫자_덩어리만_뽑는다() {
		String 한글번호 = "표준입학허가서 번호: 제 2013-0001 호";
		PermitJudgement r = judge.judge(글자(제목, 국적, 학교, 한글번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.normalizedNumber()).isEqualTo(PermitNumberHash.normalize("2013-0001"));
	}

	@Test
	void 제목_줄만_있고_번호_라벨이_없으면_번호로_오인하지_않는다() {
		PermitJudgement r = judge.judge(제목 + " 2013-0001\n" + 국적 + "\n" + 학교);
		assertThat(r.result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 항목_번호가_붙고_값이_다음_줄에_있는_견본_모양도_통과한다() {
		String 견본 = """
				표준입학허가서
				Certificate of Admission
				1. 성명(Name)
				김가짜
				2. 학교명(School name)
				연세대학교
				학교주소(School address)
				서울특별시 어딘가 Korea
				국적(Country of citizenship) 성별(Gender)
				프 랑 스 □ 남성(Male) ☑ 여성(Female)
				출생국가(Country of birth) 생년월일(Date of birth)
				대한민국 2000-01-01
				입학허가번호(Admission Number)
				9999888877776666
				표준입학허가서 번호(Certificate of Admission No.)
				0130000000000001
				""";
		PermitJudgement r = judge.judge(견본);
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("연세대학교"));
		assertThat(r.normalizedNumber()).isEqualTo("0130000000000001");
	}

	@Test
	void 학교_주소의_Korea_University_때문에_탈락하지_않는다() {
		String 글 = """
				표준입학허가서
				학교명(School name) 중앙대학교
				학교주소(School address) Korea University street 1
				국적(Country of citizenship) 베트남
				표준입학허가서 번호 0130000000000002
				""";
		// 중앙대는 별칭에 없으니 학교에서만 걸리고, 국적은 주소의 Korea에 속지 않고 통과한다
		assertThat(judge.judge(글).result()).isEqualTo(PermitMachineResult.SCHOOL_NOT_MATCHED);
	}

	// ---- 경계 사례 (실제 견본 모양을 흉내 낸 지어낸 값) ----

	@Test
	void 두_칸이_한_줄에_붙은_2026형_견본_국적은_다음_줄_값을_쓴다() {
		// 무엇을: "B. 국적(...)  C. 출생국가(...)" 다음 줄에 값(베트남)이 오는 모양. 왜: 항목 번호 "c."가 값으로 오인되지 않는지 본다.
		String 글 = """
				표준입학허가서
				A. 학교명(School name)
				한양대학교
				B. 국적(Country of citizenship)  C. 출생국가(Country of birth)
				베트남
				G. 입학허가번호(Admission Number)
				9999888877776666
				표준입학허가서 번호(Certificate of Admission No.)
				0130000000000003
				""";
		PermitJudgement r = judge.judge(글);
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("한양대학교"));
		assertThat(r.normalizedNumber()).isEqualTo("0130000000000003");
	}

	@Test
	void 두_칸이_한_줄에_붙은_2026형_견본에서_한국_국적은_걸러진다() {
		// 무엇을: 같은 모양에서 국적 값이 대한민국이면 NATIONALITY_UNCLEAR. 왜: 항목 번호 때문에 한국 국적이 통과하면 안 된다.
		String 글 = """
				표준입학허가서
				A. 학교명(School name)
				한양대학교
				B. 국적(Country of citizenship)  C. 출생국가(Country of birth)
				대한민국
				표준입학허가서 번호(Certificate of Admission No.)
				0130000000000003
				""";
		assertThat(judge.judge(글).result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 입학허가번호_칸만_있는_옛_양식은_NUMBER_NOT_FOUND() {
		String 글 = """
				표준입학허가서
				학교명(School name)
				연세대학교
				국적(Country of citizenship)
				프랑스
				G. 입학허가번호(Admission Number)
				9999888877776666
				""";
		PermitJudgement r = judge.judge(글);
		assertThat(r.result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
		assertThat(r.normalizedNumber()).isNull();
	}

	@Test
	void 영문_대문자_라벨과_전각_문자와_콜론_유무를_가리지_않는다() {
		String 대문자 = "CERTIFICATE OF ADMISSION\nCOUNTRY OF CITIZENSHIP: CHINA\nSCHOOL NAME: YONSEI UNIVERSITY\nCERTIFICATE OF ADMISSION NO. 0130000000000004";
		assertThat(judge.judge(대문자).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		// 전각 영문·숫자·콜론(NFKC로 일반 문자가 된다)
		String 전각 = "표준입학허가서\n국적（Country of citizenship）：ＣＨＩＮＡ\n학교명（School name）：ＹＯＮＳＥＩ ＵＮＩＶＥＲＳＩＴＹ\n표준입학허가서 번호（Certificate of Admission No.）：０１３０００００００００００５";
		PermitJudgement r = judge.judge(전각);
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		// 전각 숫자는 일반 숫자로 바뀐 값과 같아야 한다
		assertThat(r.normalizedNumber()).isEqualTo(PermitNumberHash.normalize(java.text.Normalizer.normalize("０１３０００００００００００５", java.text.Normalizer.Form.NFKC)));
		// 콜론 없이 공백만 있는 경우
		String 콜론없음 = "표준입학허가서\n국적(Country of citizenship) 베트남\n학교명(School name) 연세대학교\n표준입학허가서 번호(Certificate of Admission No.) 0130000000000006";
		assertThat(judge.judge(콜론없음).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 학교_값_뒤에_꼬리가_붙으면_정확히_일치가_아니므로_SCHOOL_NOT_MATCHED() {
		// 규칙이 "별칭과 정확히 일치"이므로 "(Seoul)" 꼬리가 붙은 값은 불일치가 맞다(오탐을 막는 쪽)
		PermitJudgement r = judge.judge(글자(제목, 국적, "학교명(School name): 연세대학교 (Seoul)", 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.SCHOOL_NOT_MATCHED);
		assertThat(r.detectedSchoolId()).isNull();
	}

	@Test
	void ALL_PASSED가_아니면_정규화_번호는_항상_null() {
		// 무엇을: 국적에서 떨어진 글자에도 번호가 담기지 않는다. 왜: 쓸데없는 개인정보를 들고 다니지 않으려고.
		PermitJudgement r = judge.judge(글자(제목, "국적(Country of citizenship): 대한민국", 학교, 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(r.normalizedNumber()).isNull();
	}

	@Test
	void 별칭_끝의_점은_항목_번호로_오인해_자르지_않는다() {
		// 무엇을: "Korea Univ."의 마지막 점이 남아야 별칭 'korea univ.'와 일치한다. 왜: 항목 번호 지우기가 너무 많이 지우는지 본다.
		PermitJudgement r = judge.judge(글자(제목, 국적, "학교명(School name): Korea Univ.", 번호));
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.detectedSchoolId()).isEqualTo(학교id("고려대학교"));
	}

	@Test
	void 번호_값_끝에_호_점이_붙어도_번호가_같다() {
		String 글 = 글자(제목, 국적, 학교, "표준입학허가서 번호(Certificate of Admission No.): 제 2013-0001 호.");
		PermitJudgement r = judge.judge(글);
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.normalizedNumber()).isEqualTo(PermitNumberHash.normalize("2013-0001"));
	}

	@Test
	void 두_칸_붙은_줄에서_다음_줄_국적_값이_실제로_쓰인다() {
		// 무엇을: 다음 줄이 "대 한 민 국"이면 걸리고 "프 랑 스"면 통과. 왜: 국적 값이 "c."가 아니라 진짜 값으로 판정되는지 드러낸다.
		String 틀 = "표준입학허가서\nA. 학교명(School name)\n한양대학교\nB. 국적(Country of citizenship)  C. 출생국가(Country of birth)\n%s\n표준입학허가서 번호 0130000000000007";
		assertThat(judge.judge(틀.formatted("대 한 민 국")).result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(judge.judge(틀.formatted("프 랑 스")).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		// 다음 줄이 없으면 값이 비어 불통과(예전에는 "c."가 값이라 통과했다)
		assertThat(judge.judge("표준입학허가서\nB. 국적(Country of citizenship)  C. 출생국가(Country of birth)").result())
				.isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	// ---- 전면 정리 후 재검사: 잘못 통과(한국 국적이 통과)와 잘못 탈락(외국 국적이 탈락) 찾기 ----

	// 학교·번호는 정상 줄을 붙이고 국적 부분만 바꿔 끼우는 도우미
	private PermitMachineResult 국적만_바꿔(String 국적부분) {
		String 글 = "표준입학허가서\n학교명(School name): 연세대학교\n" + 국적부분 + "\n표준입학허가서 번호(Certificate of Admission No.): 0130000000000009";
		return judge.judge(글).result();
	}

	@Test
	void 다음_줄_값이_한국_계열이면_여러_모양이어도_NATIONALITY_UNCLEAR() {
		// 무엇을: 값이 다음 줄에 있고 한국을 가리키는 여러 표기. 왜: 한국 국적이 통과하면 가장 위험하다.
		String[] 값들 = { "KOREA (South)", "Korean", "S. Korea", "R.O.K", "ROK", "DPRK", "대한 민국", "대한민국(Republic of Korea)", "조선민주주의인민공화국", "ＫＯＲＥＡ", "Corea", "韓國", "(대한민국" };
		for (String 값 : 값들) {
			assertThat(국적만_바꿔("국적(Country of citizenship)\n" + 값)).as("다음 줄 " + 값).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			assertThat(국적만_바꿔("국적(Country of citizenship): " + 값)).as("같은 줄 " + 값).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		}
	}

	@Test
	void 한국_값이_성별_칸과_함께_다음_줄에_붙어_있어도_걸린다() {
		String 글 = "국적(Country of citizenship) 성별(Gender)\n대 한 민 국 □ 남성(Male) ☑ 여성(Female)";
		assertThat(국적만_바꿔(글)).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		// 같은 줄 값이 기호뿐이라 빈 값으로 보고 다음 줄을 읽는 경우
		assertThat(국적만_바꿔("국적(Country of citizenship): □\n대한민국")).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 한국_국적_값이_두_줄로_나뉘어_읽혀도_걸린다() {
		// 재현: "Republic of" / "Korea" 두 줄. 첫 줄만 값으로 쓰면 한국이 안 보여 통과해 버릴 수 있다.
		// 한 번에 세 경우를 모두 확인하려고 soft 단언을 쓴다
		org.assertj.core.api.SoftAssertions.assertSoftly(s -> {
			s.assertThat(국적만_바꿔("국적(Country of citizenship): Republic of\nKorea")).as("같은 줄 + 다음 줄").isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			s.assertThat(국적만_바꿔("국적(Country of citizenship)\nRepublic of\nKorea")).as("라벨만 + 두 줄").isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			s.assertThat(국적만_바꿔("국적(Country of citizenship): 대한\n민국")).as("한글 두 줄").isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		});
	}

	@Test
	void 국적_라벨이_두_번_나오고_한쪽이_한국이면_걸린다() {
		String 글 = "국적(Country of citizenship): 베트남\n국적(Country of citizenship): 대한민국";
		assertThat(국적만_바꿔(글)).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 일반_외국_국적은_잘못_탈락하지_않는다() {
		// kor·rok·prk가 우연히 들어 있는 나라가 없는지 확인한다(Morocco 등)
		String[] 값들 = { "중국", "CHINA", "Uzbekistan", "몽골", "Morocco", "Suriname", "Mongolia", "Turkey", "Pakistan", "Kazakhstan", "Indonesia", "일본", "Japan", "United States of America", "Burkina Faso", "Papua New Guinea", "Czech Republic", "Kyrgyz Republic", "Ukraine", "Tajikistan" };
		for (String 값 : 값들) {
			assertThat(국적만_바꿔("국적(Country of citizenship): " + 값)).as("같은 줄 " + 값).isEqualTo(PermitMachineResult.ALL_PASSED);
			assertThat(국적만_바꿔("국적(Country of citizenship)\n" + 값)).as("다음 줄 " + 값).isEqualTo(PermitMachineResult.ALL_PASSED);
		}
	}

	@Test
	void 두_견본_모양은_정리_뒤에도_ALL_PASSED이고_국적_값이_실제로_쓰인다() {
		String 견본2013 = "표준입학허가서\n2. 학교명(School name)\n연세대학교\n국적(Country of citizenship) 성별(Gender)\n프 랑 스 □ 남성(Male) ☑ 여성(Female)\n출생국가(Country of birth)\n대한민국\n표준입학허가서 번호(Certificate of Admission No.) : 0130000000000010";
		assertThat(judge.judge(견본2013).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		// 2013형에서 국적 칸만 한국으로 바꾸면 걸려야 한다(출생국가 대한민국은 무시되지만 국적 칸 값은 반영)
		assertThat(judge.judge(견본2013.replace("프 랑 스", "대 한 민 국")).result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		String 견본2026 = "표준입학허가서\nA. 학교명(School name)\n한양대학교\nB. 국적(Country of citizenship)  C. 출생국가(Country of birth)\n베트남\n대한민국\n표준입학허가서 번호(Certificate of Admission No.)\n0130000000000011";
		assertThat(judge.judge(견본2026).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(judge.judge(견본2026.replace("\n베트남\n", "\n대한민국\n")).result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 번호_라벨_줄에_날짜가_붙어도_날짜를_번호로_쓰지_않는다() {
		String 앞 = "표준입학허가서\n국적(Country of citizenship): 베트남\n학교명(School name): 연세대학교\n";
		// 진짜 번호가 앞에 있고 날짜가 뒤에 붙은 경우: 번호는 앞의 값
		PermitJudgement 뒤날짜 = judge.judge(앞 + "표준입학허가서 번호(Certificate of Admission No.): 0130000000000012 발급일 2013-01-01");
		assertThat(뒤날짜.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(뒤날짜.normalizedNumber()).isEqualTo("0130000000000012");
		// 라벨 줄이 비고 다음 줄이 날짜뿐이면 번호 없음
		assertThat(judge.judge(앞 + "표준입학허가서 번호(Certificate of Admission No.)\n2013-01-01").result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
		// 라벨 같은 줄에 날짜만 있는 경우(재현용): 날짜를 번호로 쓰면 안 된다
		assertThat(judge.judge(앞 + "표준입학허가서 번호(Certificate of Admission No.): 2013-01-01").result()).as("같은 줄 날짜만").isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 영문_번호_라벨_뒤에_영문자가_붙으면_번호_라벨이_아니다() {
		String 글 = "표준입학허가서\n국적(Country of citizenship): 베트남\n학교명(School name): 연세대학교\nCertificate of Admission Not valid 0130000000000013";
		assertThat(judge.judge(글).result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 공백으로_끊어_읽힌_번호도_하나로_이어_붙인다() {
		String 글 = "표준입학허가서\n국적(Country of citizenship): 베트남\n학교명(School name): 연세대학교\n표준입학허가서 번호(Certificate of Admission No.): 0130 0000 0001";
		PermitJudgement r = judge.judge(글);
		assertThat(r.result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		assertThat(r.normalizedNumber()).isEqualTo(PermitNumberHash.normalize("013000000001"));
	}

	// ---- 허용 목록 규칙 재검사 ----

	@Test
	void 흔한_유학생_국적은_한국어와_영어_모두_통과한다() {
		// 무엇을: 통과해야 할 나라 이름이 허용 목록에서 빠지지 않았는지. 왜: 빠지면 정상 신청이 관리자 대기로 밀린다. 탈락 목록을 한 번에 보려고 soft 단언을 쓴다.
		String[] 값들 = { "중국", "China", "베트남", "Vietnam", "Viet Nam", "베트남(Vietnam)", "우즈베키스탄", "Uzbekistan", "몽골", "Mongolia", "일본", "Japan", "네팔", "Nepal",
				"미얀마", "Myanmar", "인도네시아", "Indonesia", "파키스탄", "Pakistan", "방글라데시", "Bangladesh", "카자흐스탄", "Kazakhstan", "러시아", "Russia", "미국", "United States",
				"프랑스", "France", "독일", "Germany", "대만", "Taiwan", "필리핀", "Philippines", "태국", "Thailand", "말레이시아", "Malaysia", "인도", "India", "스리랑카", "Sri Lanka",
				"키르기스스탄", "Kyrgyzstan", "타지키스탄", "Tajikistan", "이란", "Iran", "튀르키예", "터키", "Turkey", "Turkiye", "캐나다", "Canada", "호주", "오스트레일리아", "Australia",
				"영국", "United Kingdom", "홍콩", "Hong Kong", "Morocco", "Suriname" };
		org.assertj.core.api.SoftAssertions.assertSoftly(s -> {
			for (String 값 : 값들) {
				s.assertThat(국적만_바꿔("국적(Country of citizenship): " + 값)).as("탈락한 국적: " + 값).isEqualTo(PermitMachineResult.ALL_PASSED);
			}
		});
	}

	@Test
	void Korea가_들어간_다른_표기도_모두_걸린다() {
		String[] 값들 = { "Korea, Republic of", "Republic of Korea (ROK)", "KOREA REP", "Korea (South)", "South Korea", "Korea, Democratic People's Republic of", "대한민국 (Korea)", "한국", "Korea" };
		org.assertj.core.api.SoftAssertions.assertSoftly(s -> {
			for (String 값 : 값들) {
				s.assertThat(국적만_바꿔("국적(Country of citizenship): " + 값)).as("통과해 버린 표기: " + 값).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
			}
		});
	}

	@Test
	void 국적_칸이_없고_다른_곳에만_나라_이름이_있으면_걸린다() {
		String 글 = "표준입학허가서\n학교명(School name): 연세대학교\n출생국가(Country of birth): 베트남\n주소: China\n표준입학허가서 번호(Certificate of Admission No.): 0130000000000020";
		assertThat(judge.judge(글).result()).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 라벨_묶음_뒤_값_묶음에서_국적_자리에_이름이_오면_걸린다() {
		// 무엇을: 라벨이 줄로 쭉 나온 뒤 값이 따로 나오는 양식에서, 국적 라벨 바로 다음 줄이 다른 칸 라벨이면 값이 없는 것이어야 한다.
		// 왜: 다른 칸 값(이름이 우연히 나라 이름 Jordan·Chad·조지아)을 국적으로 잘못 집어 통과하면 안 된다.
		String 글 = "표준입학허가서\n학교명(School name): 연세대학교\n국적(Country of citizenship)\n성명(Name)\nJordan\n표준입학허가서 번호(Certificate of Admission No.): 0130000000000021";
		assertThat(judge.judge(글).result()).as("라벨 다음 줄이 성명 라벨").isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		// 같은 줄 값에 이름이 섞이면 불통과
		assertThat(국적만_바꿔("국적(Country of citizenship): 베트남 응우옌")).as("국적 + 이름").isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적만_바꿔("국적(Country of citizenship): Chad Kim")).as("Chad Kim").isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		// 허용 목록에 없는 값
		assertThat(국적만_바꿔("국적(Country of citizenship): Atlantis")).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 두_견본_모양은_허용_목록_규칙에서도_ALL_PASSED() {
		String 견본2013 = "표준입학허가서\n2. 학교명(School name)\n연세대학교\n국적(Country of citizenship) 성별(Gender)\n프 랑 스 □ 남성(Male) ☑ 여성(Female)\n출생국가(Country of birth)\n대한민국\n표준입학허가서 번호(Certificate of Admission No.) : 0130000000000030";
		assertThat(judge.judge(견본2013).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
		String 견본2026 = "표준입학허가서\nA. 학교명(School name)\n한양대학교\nB. 국적(Country of citizenship)  C. 출생국가(Country of birth)\n베트남\n대한민국\n표준입학허가서 번호(Certificate of Admission No.)\n0130000000000031";
		assertThat(judge.judge(견본2026).result()).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 같은_줄_값이_날짜뿐이면_다음_줄을_국적으로_읽지_않는다() {
		// 무엇을: 국적 칸에 날짜가 읽히고 다음 줄에 "베트남"이 있어도 불통과. 왜: 다른 칸의 값을 국적으로 잘못 집어 통과하면 안 된다.
		assertThat(국적만_바꿔("국적(Country of citizenship): 2000-01-01\n베트남")).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적만_바꿔("국적(Country of citizenship): 2000-01-01\n대한민국")).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
	}

	@Test
	void 국적_라벨_뒤에_생년월일_칸이_붙은_줄은_다음_줄_값으로_판정한다() {
		// 같은 줄에 국적 값이 없고 옆 칸(생년월일)이 붙은 모양: 다음 줄이 한국이면 걸리고, 외국이면 통과(다음 줄이 국적 값이라고 읽는다)
		String 줄 = "국적(Country of citizenship) 생년월일(Date of birth) 2000-01-01\n";
		assertThat(국적만_바꿔(줄 + "대한민국")).isEqualTo(PermitMachineResult.NATIONALITY_UNCLEAR);
		assertThat(국적만_바꿔(줄 + "베트남")).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 번호_줄에서도_날짜만_있으면_다음_줄_번호를_가져오지_않는다() {
		String 앞 = "표준입학허가서\n국적(Country of citizenship): 베트남\n학교명(School name): 연세대학교\n";
		assertThat(judge.judge(앞 + "표준입학허가서 번호(Certificate of Admission No.): 2013-01-01\n0130000000000040").result()).isEqualTo(PermitMachineResult.NUMBER_NOT_FOUND);
	}

	@Test
	void 나라_목록_점검은_준비됨이고_Turkiye도_통과한다() {
		assertThat(judge.isCountryListReady()).isTrue();
		assertThat(국적만_바꿔("국적(Country of citizenship): Turkiye")).isEqualTo(PermitMachineResult.ALL_PASSED);
	}

	@Test
	void 결과를_문자열로_찍어도_번호가_안_나온다() {
		PermitJudgement r = judge.judge(글자(제목, 국적, 학교, 번호));
		assertThat(r.toString()).doesNotContain("0000111122223333").contains("ALL_PASSED");
	}
}
