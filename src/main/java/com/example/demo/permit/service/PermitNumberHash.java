package com.example.demo.permit.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.demo.config.PermitProperties;

// 허가서 번호의 정규화와 HMAC-SHA256 해시(hex). DB에는 번호 원문 대신 이 해시만 저장한다.
// 그냥 SHA-256이 아니라 비밀 값(PERMIT_HASH_SECRET)을 섞는 HMAC을 쓰는 이유: 허가서 번호는 가짓수가 적어서,
// DB만 새어도 번호를 하나씩 넣어 보며 해시를 맞춰 볼 수 있기 때문이다. 비밀 값이 서버에만 있으면 DB만으로는 못 되돌린다.
// 비밀 값을 바꾸면 이미 저장된 해시와 안 맞게 되므로(같은 번호 중복 승인 확인이 무력화) 운영에서는 바꾸지 않는다.
@Component
@EnableConfigurationProperties(PermitProperties.class)
public class PermitNumberHash {

    private static final int MIN_SECRET_BYTES = 32; // HMAC-SHA256 권장 최소 길이(256비트)

    // 글자(\p{L})·숫자(\p{N})가 아닌 것은 전부 지운다: 공백·하이픈·슬래시·점·밑줄 등
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final SecretKeySpec key;

    // 비밀 값이 없거나 짧으면 서버 시작 단계에서 실패한다. 오류 메시지에 값은 넣지 않는다.
    public PermitNumberHash(PermitProperties props) {
        String secret = props.hashSecret() == null ? "" : props.hashSecret().strip();
        if (secret.isEmpty()) {
            throw new IllegalStateException("PERMIT_HASH_SECRET이 비어 있습니다. 환경변수로 설정하세요 (.env.example 참고).");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "PERMIT_HASH_SECRET은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다 (.env.example 참고).");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    // 정규화: NFKC(전각·호환 문자를 일반 문자로) -> 글자·숫자만 남기기 -> 대문자.
    // 공백·하이픈·슬래시·점·밑줄과 전각 기호가 사라진다. "제…호"·"No." 같은 접두·접미어는 일반화하기 어려워 지우지 않는다:
    // 관리자는 번호의 영숫자 부분만 입력해야 한다. 결과가 비면 번호로 쓸 글자가 없다는 뜻(호출한 쪽이 400 처리).
    public static String normalize(String permitNumber) {
        String nfkc = Normalizer.normalize(permitNumber, Normalizer.Form.NFKC);
        return NOT_LETTER_OR_DIGIT.matcher(nfkc).replaceAll("").toUpperCase(Locale.ROOT);
    }

    // 이미 정규화된 번호를 받아 HMAC-SHA256 해시(hex 64자)를 만든다. Mac은 스레드 안전하지 않아 호출마다 새로 만든다.
    public String of(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // HmacSHA256은 모든 JVM이 지원, 키는 시작 때 검사했다
        }
    }
}
