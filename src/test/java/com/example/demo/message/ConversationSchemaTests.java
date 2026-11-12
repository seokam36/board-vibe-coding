package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// S14-a 리뷰 반영: V12(conversations·messages)의 이름 붙은 제약·인덱스와 CASCADE 동작을 DB에 직접 넣어 확인한다.
// 앱이 떴다는 것 자체가 Flyway V12 + ddl-auto=validate 통과의 증거다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConversationSchemaTests {

	@Autowired JdbcTemplate jdbc;
	@Autowired UserRepository userRepository;

	User 가, 나;

	@BeforeEach
	void 준비() {
		정리();
		가 = TestLogin.사용자(userRepository, "schemaa");
		나 = TestLogin.사용자(userRepository, "schemab");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations"); // 쪽지는 CASCADE로 같이 지워진다
		userRepository.deleteAll();
	}

	// 글 칸은 NULL로 둔다(글이 지워진 방과 같은 모양). 방 id를 돌려준다.
	private long 방_넣기(Long buyer, Long seller) {
		return jdbc.queryForObject("insert into conversations (market_item_id, item_title, buyer_id, seller_id, created_at, last_message_at) "
				+ "values (null, '제목', ?, ?, now(), now()) returning id", Long.class, buyer, seller);
	}

	private int 쪽지_넣기(long 방id, Long sender, String content) {
		return jdbc.update("insert into messages (conversation_id, sender_id, content, created_at) values (?, ?, ?, now())",
				방id, sender, content);
	}

	private int 센다(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void V12가_성공_기록으로_한_번_남는다() {
		assertThat(센다("select count(*) from flyway_schema_history where version = '12' and success = true")).isEqualTo(1);
	}

	@Test
	void 이름_붙은_제약이_모두_있다() {
		List<String> 이름들 = List.of("conversations_pkey", "fk_conversations_item", "fk_conversations_buyer",
				"fk_conversations_seller", "uk_conversations_item_buyer", "ck_conversations_buyer_not_seller",
				"messages_pkey", "fk_messages_conversation", "fk_messages_sender", "ck_messages_content_not_blank");
		for (String 이름 : 이름들) {
			assertThat(센다("select count(*) from pg_constraint where conname = ?", 이름)).as("제약 " + 이름).isEqualTo(1);
		}
	}

	@Test
	void 이름_붙은_인덱스가_모두_있다() {
		List<String> 이름들 = List.of("idx_conversations_buyer_id", "idx_conversations_seller_id",
				"idx_messages_conversation_id_id", "idx_messages_sender_id", "uk_conversations_item_buyer");
		for (String 이름 : 이름들) {
			assertThat(센다("select count(*) from pg_indexes where schemaname = current_schema() and indexname = ?", 이름))
					.as("인덱스 " + 이름).isEqualTo(1);
		}
	}

	@Test
	void 자기_자신과의_방은_ck_conversations_buyer_not_seller가_거절하고_한쪽이_NULL이면_통과한다() {
		assertThatThrownBy(() -> 방_넣기(가.getId(), 가.getId())).hasMessageContaining("ck_conversations_buyer_not_seller");
		assertThat(방_넣기(가.getId(), 나.getId())).isPositive();
		assertThat(방_넣기(null, 나.getId())).as("탈퇴해 NULL이 된 방 모양은 허용").isPositive();
	}

	@Test
	void 공백뿐인_쪽지는_ck_messages_content_not_blank가_거절한다() {
		long 방 = 방_넣기(가.getId(), 나.getId());
		assertThatThrownBy(() -> 쪽지_넣기(방, 가.getId(), "   ")).hasMessageContaining("ck_messages_content_not_blank");
		assertThatThrownBy(() -> 쪽지_넣기(방, 가.getId(), "")).hasMessageContaining("ck_messages_content_not_blank");
		assertThat(쪽지_넣기(방, 가.getId(), "안녕")).isEqualTo(1);
	}

	@Test
	void 방을_지우면_쪽지가_CASCADE로_같이_지워진다() {
		long 방 = 방_넣기(가.getId(), 나.getId());
		쪽지_넣기(방, 가.getId(), "하나");
		쪽지_넣기(방, 나.getId(), "둘");
		assertThat(센다("select count(*) from messages where conversation_id = ?", 방)).isEqualTo(2);

		jdbc.update("delete from conversations where id = ?", 방);

		assertThat(센다("select count(*) from messages where conversation_id = ?", 방)).isZero();
	}

	// 서비스는 이 제약 이름으로 404/401을 구분한다(ConversationService의 catch). 이름이 바뀌면 이 테스트가 알려 준다.
	// (지금은 서비스가 회원 행을 먼저 잡아 두어 이 FK 위반이 경쟁으로는 거의 안 나지만, 마지막 방어선으로 남겨 둔 분기다)
	@Test
	void 없는_판매자_구매자_보낸사람은_각자_이름_붙은_FK가_거절한다() {
		assertThatThrownBy(() -> 방_넣기(가.getId(), 999_999L)).hasMessageContaining("fk_conversations_seller");
		assertThatThrownBy(() -> 방_넣기(999_999L, 가.getId())).hasMessageContaining("fk_conversations_buyer");
		long 방 = 방_넣기(가.getId(), 나.getId());
		assertThatThrownBy(() -> 쪽지_넣기(방, 999_999L, "안녕")).hasMessageContaining("fk_messages_sender");
	}

	@Test
	void 보낸_회원이_탈퇴하면_sender만_NULL이_되고_쪽지는_남는다() {
		long 방 = 방_넣기(가.getId(), 나.getId());
		쪽지_넣기(방, 가.getId(), "안녕");

		jdbc.update("delete from users where id = ?", 가.getId());

		assertThat(센다("select count(*) from messages where conversation_id = ? and sender_id is null", 방)).isEqualTo(1);
		assertThat(센다("select count(*) from conversations where id = ? and buyer_id is null", 방)).isEqualTo(1);
	}
}
