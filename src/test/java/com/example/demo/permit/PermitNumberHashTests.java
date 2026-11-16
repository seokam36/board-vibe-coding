package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.demo.config.PermitProperties;
import com.example.demo.permit.service.PermitNumberHash;

// S20-b 완료 조건 9: 허가서 번호 정규화(NFKC) + HMAC-SHA256 해시 단위 테스트
class PermitNumberHashTests {

	static final String 비밀A = "a".repeat(32);
	static final String 비밀B = "b".repeat(32);

	private PermitNumberHash 해셔(String secret) {
		return new PermitNumberHash(new PermitProperties(secret));
	}

	@Test
	void 공백과_하이픈을_지우고_대문자로_바꾼다() {
		assertThat(PermitNumberHash.normalize("ab-12 3")).isEqualTo("AB123");
		assertThat(PermitNumberHash.normalize("  a b\t-\n1 ")).isEqualTo("AB1");
	}

	@Test
	void 슬래시_점_밑줄도_지운다() {
		assertThat(PermitNumberHash.normalize("ab/12.3_4")).isEqualTo("AB1234");
	}

	@Test
	void 유니코드_공백을_지운다() {
		assertThat(PermitNumberHash.normalize("AB 12　3 ")).isEqualTo("AB123");
	}

	@Test
	void 유니코드_대시를_지운다() {
		assertThat(PermitNumberHash.normalize("A‐B‑C‒D–E—F―G−1")).isEqualTo("ABCDEFG1");
	}

	@Test
	void 전각_문자는_NFKC로_일반_문자가_된다() {
		assertThat(PermitNumberHash.normalize("ＡＢ１２")).isEqualTo("AB12");
		assertThat(PermitNumberHash.normalize("ＡＢ－１２，３")).isEqualTo("AB123");
	}

	@Test
	void 지울_글자만_있으면_빈_문자열이다() {
		assertThat(PermitNumberHash.normalize("- -")).isEmpty();
		assertThat(PermitNumberHash.normalize("   ")).isEmpty();
		assertThat(PermitNumberHash.normalize("-/._")).isEmpty();
	}

	@Test
	void 표기가_달라도_정규화하면_같은_해시다() {
		PermitNumberHash h = 해셔(비밀A);
		assertThat(h.of(PermitNumberHash.normalize("ab-12 3"))).isEqualTo(h.of(PermitNumberHash.normalize("ＡＢ123")));
	}

	@Test
	void 같은_입력_같은_비밀이면_같은_해시이고_다른_입력은_다른_해시다() {
		PermitNumberHash h = 해셔(비밀A);
		assertThat(h.of("AB123")).isEqualTo(h.of("AB123"));
		assertThat(h.of("AB123")).isEqualTo(해셔(비밀A).of("AB123"));
		assertThat(h.of("AB123")).isNotEqualTo(h.of("AB124"));
	}

	@Test
	void 해시는_소문자_hex_64자다() {
		assertThat(해셔(비밀A).of("AB123")).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	void 같은_입력이어도_비밀_값이_다르면_해시가_다르다() {
		assertThat(해셔(비밀A).of("AB123")).isNotEqualTo(해셔(비밀B).of("AB123"));
	}

	@Test
	void 비밀_값이_평문_SHA256이_아니다() throws Exception {
		String 평문 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest("AB123".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		assertThat(해셔(비밀A).of("AB123")).isNotEqualTo(평문);
	}

	@Test
	void 비밀_값이_비어_있으면_생성이_실패하고_메시지에_값이_없다() {
		for (String s : new String[] { "", "   ", null }) {
			assertThatThrownBy(() -> 해셔(s)).isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void 비밀_값이_32바이트_미만이면_생성이_실패하고_메시지에_값이_없다() {
		String 짧음 = "short-secret-VALUE-31bytes-xxxxx"; // 32자 -> 아래서 31자로
		String s31 = 짧음.substring(0, 31);
		assertThatThrownBy(() -> 해셔(s31)).isInstanceOf(IllegalStateException.class)
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(s31));
		// 32바이트면 통과
		assertThat(해셔(짧음).of("A")).hasSize(64);
	}

	@Test
	void PermitProperties_toString에_비밀_값이_없다() {
		String secret = "TOPSECRET-" + "x".repeat(30);
		assertThat(new PermitProperties(secret).toString()).doesNotContain(secret).doesNotContain("TOPSECRET");
	}
}
