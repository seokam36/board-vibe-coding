package com.example.demo.permit.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.permit.domain.PermitMachineResult;
import com.example.demo.school.repository.SchoolNameAliasRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 판독기가 읽어 온 허가서 글자(String)를 4가지 규칙으로 판정한다 (S20-d1). 순서: 제목 -> 국적 -> 학교 -> 번호.
// 처음 걸린 규칙의 결과 코드 하나만 돌려주고, 모두 통과하면 ALL_PASSED.
// 제목만 글자 전체에서 찾고, 국적·학교·번호는 "라벨(항목 이름)이 있는 줄"의 값만 본다. 주소 속 "Korea"나 "khu" 같은 말에 속지 않으려는 것이다.
// 학교만 예외로, 같은 줄 값으로 별칭을 못 찾으면 라벨 바로 다음 줄도 한 번 더 후보로 본다(라벨의 영어 설명이 깨져 읽힌 경우, S20-h).
//
// 국적 규칙은 "허용 목록" 방식이다: 값이 외국 나라 이름 목록에 있어야 통과한다.
// 처음엔 "한국 말이 안 보이면 통과"(블랙리스트)로 만들었는데, 판독기가 값을 엉뚱한 줄·칸으로 읽으면(이름·날짜·안내문 등)
// 한국 말이 안 보여서 그대로 통과하는 구멍이 계속 나왔다. 목록 방식은 값을 엉뚱하게 집으면 "목록에 없음"이라 관리자 대기로 떨어진다(안전한 쪽).
// 목록에 없는 진짜 외국 국적이 대기로 가는 것은 관리자가 보면 되니 괜찮다.
//
// 읽은 글자·번호는 개인정보라 로그를 일절 남기지 않는다. 중복 번호(NUMBER_DUPLICATE)·판독 실패(UNREADABLE)는 이 부품의 일이 아니다(S20-d2).
// d2 메모: 이 부품은 DB를 읽기만 하니 신청 INSERT가 커밋된 뒤, 트랜잭션 밖에서 부른다. 빈 글자는 d2가 먼저 UNREADABLE로 처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PermitJudge {

    private static final int MIN_NUMBER_LENGTH = 4; // 정규화한 번호의 최소 길이

    // 라벨 모양. 글자는 미리 전부 소문자로 바꿔 두므로 소문자로만 쓴다. 라벨 앞에 "2. "·"A. " 같은 항목 번호가 붙어도 찾는다.
    // 한글 라벨 바로 뒤의 영문 설명 "(Country of citizenship)"까지 라벨로 보고 함께 먹는다(값이 아니므로). 설명이 틀리게 읽히면 값으로 남아 불통과가 된다.
    private static final Pattern NATIONALITY_LABEL =
            Pattern.compile("국적(\\s*\\(\\s*country\\s*of\\s*citizenship\\s*\\))?|country\\s*of\\s*citizenship\\s*\\)?");
    private static final Pattern SCHOOL_LABEL =
            Pattern.compile("학교명(\\s*\\(\\s*school\\s*name\\s*\\))?|school\\s*name\\s*\\)?");
    // "표준입학허가서" 뒤에 "번호"가 있어야 한다: 제목 줄의 "표준입학허가서"만으로는 번호 라벨이 아니다. "입학허가번호"는 다른 칸이라 안 걸린다.
    // 영문은 "no" 뒤에 영문자가 오면("not valid ...") 라벨이 아니다.
    private static final Pattern NUMBER_LABEL = Pattern.compile(
            "표준\\s*입학\\s*허가서\\s*번호(\\s*\\(\\s*certificate\\s*of\\s*admission\\s*no\\.?\\s*\\))?"
                    + "|certificate\\s*of\\s*admission\\s*no(?![a-z])\\.?\\s*\\)?");

    // 값 뒤에 다른 칸의 라벨·체크박스·성별이 붙어 읽힐 때 거기서 자르기 위한 말(판독기가 두 칸을 한 줄로 읽는 경우)
    private static final List<String> NATIONALITY_CUTS = List.of("출생국가", "country of birth", "성별", "gender", "생년월일", "date of birth",
            "□", "☐", "☑", "☒", "✓", "✔", "■", "남성", "여성", "female", "male");
    private static final List<String> SCHOOL_CUTS = List.of("학교주소", "school address", "학교인가번호");
    private static final List<String> NO_CUTS = List.of();

    // 값 끝에 남은 항목 번호 조각: 줄 처음이나 공백 뒤의 "한 글자(영문·숫자) + 점" ("c.", "2.")
    private static final Pattern TRAILING_ITEM_NUMBER = Pattern.compile("(^|\\s)[a-z0-9]\\.$");

    // "다음 줄"이 값이 아니라 다른 칸의 라벨이면 값으로 쓰지 않는다. 라벨에 자주 쓰는 말이 있는 줄이 그렇다.
    // "학교" 한 단어는 "숭실대학교" 같은 값에도 있어서 "학교명"처럼 길게만 쓴다. 영문은 단어로만 본다("Suriname"의 name은 제외).
    private static final Pattern LABEL_WORDS = Pattern.compile(
            "성명|생년월일|성별|출생|주소|여권|입학허가|학교명|학교인가|국적|번호"
                    + "|\\b(?:name|date|school|number|gender|birth|address|passport)\\b");
    // 날짜 모양(2000-01-01 등): 번호 값으로 쓰면 안 된다. 값 안에 날짜가 나오면 그 앞까지만 쓴다(번호 뒤에 날짜가 붙어 읽힌 경우).
    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}");

    // 허용 목록에 손으로 더한 나라 표기. JDK 이름 목록은 공식 이름이라 사람들이 허가서에 자주 쓰는 표기와 다른 것을 채운다.
    // (JDK: 튀르키예/Türkiye, Czechia, United States, Kyrgyzstan, 오스트레일리아 등). 한국 계열(KR·KP)은 절대 넣지 않는다.
    private static final List<String> EXTRA_COUNTRY_NAMES = List.of(
            "turkey",
            "turkiye", // JDK의 "Türkiye"는 ü가 있어 ü 없이 쓴 표기를 따로 넣는다
            "터키",
            "united states of america",
            "usa",
            "uk",
            "czech republic",
            "kyrgyz republic",
            "myanmar",
            "burma",
            "몽고",
            "호주",
            "russian federation",
            "congo",
            "콩고",
            "ivory coast",
            "중화인민공화국",
            "people's republic of china",
            "hong kong",
            "홍콩",
            "macau",
            "마카오",
            "east timor");

    // 외국 나라 이름 허용 목록(정규화한 글자). 앱이 뜰 때 한 번만 만든다.
    private static final Set<String> COUNTRY_NAMES = buildCountryNames();

    private final SchoolNameAliasRepository aliasRepository;

    // 앱이 뜰 때 나라 이름 목록이 제대로 만들어졌는지 확인한다. 운영 JRE에 한국어 로케일 데이터가 없으면 한글 나라 이름이 전부 빠져서
    // 한글로 쓴 허가서가 조용히 전부 관리자 대기로 가기 때문이다. 앱 시작은 막지 않고 경고 한 줄만 남긴다(개인정보 없음).
    @PostConstruct
    void warnIfCountryListIncomplete() {
        if (!isCountryListReady()) {
            log.warn("허가서 국적 목록에 한국어 나라 이름이 없음 - JRE 언어 데이터 확인 필요");
        }
    }

    // 목록에 한국어(베트남·중국)와 영어(vietnam) 이름이 모두 들어 있는가
    public boolean isCountryListReady() {
        return COUNTRY_NAMES.contains(compactKey("베트남"))
                && COUNTRY_NAMES.contains(compactKey("vietnam"))
                && COUNTRY_NAMES.contains(compactKey("중국"));
    }

    // 조회만 한다(별칭 표 읽기)
    @Transactional(readOnly = true)
    public PermitJudgement judge(String rawText) {
        // 글자가 없으면 제목도 없는 것이므로 NO_TITLE. 판독 실패(UNREADABLE)로 볼지는 호출하는 쪽(d2)이 정한다.
        if (rawText == null || rawText.isBlank()) {
            return new PermitJudgement(PermitMachineResult.NO_TITLE, null, null);
        }

        // NFKC: 전각 문자·괄호를 일반 문자로, 소문자: 비교를 쉽게. 그다음 줄 단위로 자른다.
        String text = Normalizer.normalize(rawText, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> lines = text.lines().toList();

        // 학교는 다른 항목이 떨어져도 관리자 참고용으로 알아 둔다
        Long schoolId = findSchoolId(findValues(lines, SCHOOL_LABEL, SCHOOL_CUTS, true));

        if (!hasTitle(text)) {
            return new PermitJudgement(PermitMachineResult.NO_TITLE, schoolId, null);
        }
        if (!isForeignNationality(findValues(lines, NATIONALITY_LABEL, NATIONALITY_CUTS, false))) {
            return new PermitJudgement(PermitMachineResult.NATIONALITY_UNCLEAR, schoolId, null);
        }
        if (schoolId == null) {
            return new PermitJudgement(PermitMachineResult.SCHOOL_NOT_MATCHED, null, null);
        }
        String number = findNumber(findValues(lines, NUMBER_LABEL, NO_CUTS, false));
        if (number == null) {
            return new PermitJudgement(PermitMachineResult.NUMBER_NOT_FOUND, schoolId, null);
        }
        return new PermitJudgement(PermitMachineResult.ALL_PASSED, schoolId, number);
    }

    // 규칙 1: 제목. 공백을 모두 뺀 글자에 제목이 있으면 통과("표 준 입 학 허 가 서"처럼 띄어 읽혀도 됨)
    private boolean hasTitle(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("표준입학허가서") || compact.contains("certificateofadmission");
    }

    // 규칙 2: 국적. 국적 라벨이 나오는 "모든" 줄의 값이 전부 외국 나라 이름 목록에 있어야 통과.
    // 라벨이 없거나, 값이 하나라도 비었거나 목록에 없으면(한국·북한 포함) 불통과.
    private boolean isForeignNationality(List<String> values) {
        if (values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (!isKnownCountry(value)) {
                return false;
            }
        }
        return true;
    }

    // 값 전체가 나라 이름이어야 한다. "베트남(Vietnam)"처럼 괄호로 두 표기를 쓰면 괄호 앞·안이 각각 목록에 있어야 한다.
    private boolean isKnownCountry(String value) {
        int open = value.indexOf('(');
        if (open < 0) {
            return COUNTRY_NAMES.contains(compactKey(value));
        }
        int close = value.indexOf(')', open);
        if (close != value.length() - 1) { // 닫는 괄호가 없거나 그 뒤에 글자가 더 있으면 불통과
            return false;
        }
        return COUNTRY_NAMES.contains(compactKey(value.substring(0, open)))
                && COUNTRY_NAMES.contains(compactKey(value.substring(open + 1, close)));
    }

    // 비교용 글자: 소문자로 바꾸고 글자·숫자가 아닌 것(공백·점·괄호·기호)을 모두 지운다. "프 랑 스" -> "프랑스", "U.S.A" -> "usa"
    private static String compactKey(String text) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            if (Character.isLetterOrDigit(lower.charAt(i))) {
                key.append(lower.charAt(i));
            }
        }
        return key.toString();
    }

    // JDK가 가진 나라 목록(ISO 코드)에서 한국어·영어 이름을 모은다(새 라이브러리 없이). KR(대한민국)·KP(북한)는 일부러 뺀다.
    private static Set<String> buildCountryNames() {
        Set<String> names = new HashSet<>();
        for (String code : Locale.getISOCountries()) {
            if (code.equals("KR") || code.equals("KP")) {
                continue;
            }
            Locale country = Locale.of("", code);
            names.add(compactKey(country.getDisplayCountry(Locale.KOREAN)));
            names.add(compactKey(country.getDisplayCountry(Locale.ENGLISH)));
        }
        for (String extra : EXTRA_COUNTRY_NAMES) {
            names.add(compactKey(extra));
        }
        names.remove(""); // 혹시 빈 이름이 섞이면 빈 값이 통과하지 않게
        return names;
    }

    // 규칙 3: 학교. 앞뒤 공백 제거 + 연속 공백 한 칸으로 만든 뒤 별칭 표와 "정확히 일치"할 때만 그 학교 id.
    private Long findSchoolId(List<String> values) {
        for (String value : values) {
            String alias = value.strip().replaceAll("\\s+", " ");
            if (alias.isEmpty()) {
                continue;
            }
            Long found = aliasRepository.findByAlias(alias)
                    .map(a -> a.getSchool().getId())
                    .orElse(null);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // 규칙 4: 번호. 라벨 줄들 중 번호를 뽑을 수 있는 첫 값을 쓴다. 없으면 null.
    private String findNumber(List<String> values) {
        for (String value : values) {
            String number = extractNumber(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    // 값에서 번호를 뽑는다. 영문·숫자·하이픈·공백으로 이어진 덩어리에서 숫자가 든 첫 조각부터 시작해,
    // 바로 이어지는 숫자 조각·하이픈을 붙여서 하나로 본다("0130 0000 0001"·"2013 - 0001", "제 2013-0001 호"는 한글에서 끊김).
    // 숫자 없는 조각만 있는 덩어리는 건너뛴다: 다른 칸의 영어 단어를 번호로 잘못 집지 않으려고.
    // 정규화(PermitNumberHash.normalize, 관리자 입력과 같은 방식) 결과가 4자 미만이면 번호로 인정하지 않는다(null).
    private String extractNumber(String value) {
        StringBuilder run = new StringBuilder(); // 번호에 쓸 수 있는 글자로만 이어진 덩어리
        for (int i = 0; i <= value.length(); i++) {
            char c = i < value.length() ? value.charAt(i) : '\n'; // 끝에서는 구분 글자를 하나 더 봐서 마지막 덩어리를 마무리
            boolean partOfNumber = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == ' ';
            if (partOfNumber) {
                run.append(c);
                continue;
            }
            String joined = joinNumberTokens(run.toString());
            if (joined != null) {
                String normalized = PermitNumberHash.normalize(joined);
                return normalized.length() >= MIN_NUMBER_LENGTH ? normalized : null;
            }
            run.setLength(0);
        }
        return null;
    }

    // 덩어리를 공백으로 나눠, 숫자가 든 첫 조각부터 "숫자가 든 조각 또는 하이픈뿐인 조각"이 이어지는 동안 붙인다. 숫자 조각이 없으면 null.
    private String joinNumberTokens(String run) {
        StringBuilder joined = new StringBuilder();
        boolean started = false;
        for (String token : run.strip().split("\\s+")) {
            boolean hasDigit = token.chars().anyMatch(Character::isDigit);
            boolean onlyHyphens = !token.isEmpty() && token.chars().allMatch(ch -> ch == '-');
            if (hasDigit || (started && onlyHyphens)) {
                joined.append(token);
                started = true;
            } else if (started) {
                break; // 숫자 조각 뒤에 일반 단어가 나오면 거기서 끝
            }
        }
        return started ? joined.toString() : null;
    }

    private boolean hasContent(String value) { // 글자나 숫자가 2자 이상이어야 "값이 있다". "|"·"-"·"□" 같은 기호만이면 빈 값
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetterOrDigit(value.charAt(i))) {
                count++;
            }
        }
        return count >= 2;
    }

    // 라벨이 나오는 "모든" 줄에서 값을 읽는다(줄마다 값 하나, 비어 있으면 "". 학교는 최대 둘: 같은 줄·다음 줄). 같은 줄 라벨 뒤에 값이 없으면 "바로 다음(비어 있지 않은) 줄"을 값으로 쓴다.
    // 다음 줄이 다른 칸의 라벨이면 값으로 안 쓴다(값은 빈 채로 둔다). 다음 줄의 날짜 뒤는 잘려 나가므로 날짜뿐인 줄도 빈 값이 된다.
    // 단, 같은 줄에 실제 값이 적혀 있었는데 날짜 때문에 잘려서 비었으면 다음 줄을 읽지 않는다
    // (번호 칸에 날짜만 적혀 있을 때 다음 줄의 엉뚱한 숫자를 번호로 집지 않으려고).
    // 옆 칸 라벨·체크박스·항목 번호("c.")뿐이라 비었을 때는 값이 원래 없던 것이므로 다음 줄을 읽는다.
    // alsoNextLine(학교 규칙 전용, S20-h): 같은 줄에 값이 있어도 다음 줄 값을 후보로 하나 더 붙인다.
    // 라벨의 영어 설명 "(School name)"이 깨진 글자로 읽혀 같은 줄에 가짜 값이 남고, 진짜 값은 다음 줄에 있는 경우를 위해서다.
    // 후보를 늘려도 별칭 표와 "정확히 일치"해야만 통과한다. 다만 같은 줄 값이 미등록 학교이고 다음 줄 전체가 다른 등록 별칭과 정확히 같으면
    // 그 학교로 통과할 수 있다 - 실제 양식에선 드물고, 같은 줄에 써도 통과하므로 새 위조 구멍은 아니다(받아들인 한계).
    // 국적·번호는 false로 둔다(기존 설계 유지).
    private List<String> findValues(List<String> lines, Pattern label, List<String> cutWords, boolean alsoNextLine) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = label.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            String wordsCut = cutAtWords(lines.get(i).substring(m.end()), cutWords); // 날짜는 아직 안 자름
            String value = cleanValue(cutAtDate(wordsCut));
            boolean valueLostToDate = !hasContent(value) && hasContent(cleanValue(wordsCut));
            if (hasContent(value) && alsoNextLine) {
                values.add(value); // 같은 줄 값은 그대로 두고, 다음 줄 값은 아래에서 별도 후보로 추가
                value = nextLineValue(lines, i, cutWords);
            } else if (!hasContent(value) && !valueLostToDate) {
                value = nextLineValue(lines, i, cutWords);
            }
            values.add(hasContent(value) ? value : "");
        }
        return values;
    }

    // 라벨 줄 바로 다음(비어 있지 않은) 줄의 값. 다음 줄이 다른 칸의 라벨이거나 없으면 ""
    private String nextLineValue(List<String> lines, int labelIndex, List<String> cutWords) {
        String next = nextNonBlankLine(lines, labelIndex);
        if (next == null) {
            return "";
        }
        String nextText = cutAtDate(cutAtWords(next, cutWords));
        return LABEL_WORDS.matcher(nextText).find() ? "" : cleanValue(nextText);
    }

    private String nextNonBlankLine(List<String> lines, int index) {
        for (int j = index + 1; j < lines.size(); j++) {
            if (!lines.get(j).isBlank()) {
                return lines.get(j);
            }
        }
        return null;
    }

    // 다른 칸의 라벨·체크박스·성별이 시작하는 곳에서 글자를 자른다(목록의 말을 차례로 찾아 그 앞까지만 남긴다)
    private String cutAtWords(String text, List<String> cutWords) {
        String result = text;
        for (String cut : cutWords) {
            int at = result.indexOf(cut);
            if (at >= 0) {
                result = result.substring(0, at);
            }
        }
        return result;
    }

    // 날짜 모양이 처음 나오는 곳 앞까지만 쓴다(번호 뒤에 발급일 같은 날짜가 붙어 읽힌 경우)
    private String cutAtDate(String text) {
        Matcher date = DATE_SHAPE.matcher(text);
        return date.find() ? text.substring(0, date.start()) : text;
    }

    // 자른 글자를 값으로 다듬는다: 앞의 ":"·")"·"."·공백 제거 -> 끝에 남은 항목 번호 조각("c.") 제거(그래야 빈 값이 되어 다음 줄을 읽는다)
    // 괄호는 지우지 않는다. 값 안의 괄호는 나라 이름 검사(isKnownCountry)가 직접 본다.
    private String cleanValue(String cut) {
        String value = cut.strip();
        int start = 0;
        while (start < value.length() && ": ).".indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        value = value.substring(start);
        return TRAILING_ITEM_NUMBER.matcher(value.strip()).replaceFirst("").strip();
    }
}
