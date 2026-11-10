package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestcontainersConfiguration;

// FK 감시: users(id)를 가리키는 모든 FK는 "탈퇴 때 처리되는 것"이어야 한다.
// 처리되는 것 = (1) DB가 ON DELETE CASCADE/SET NULL/SET DEFAULT로 알아서 처리하거나 (2) UserService.withdraw가 직접 비우는 컬럼.
// 새 FK가 생겼는데 둘 다 아니면 탈퇴가 FK 위반(500)으로 깨지므로, 이 테스트가 먼저 실패해서 알려 준다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsersForeignKeyGuardTests {

	@Autowired JdbcTemplate jdbc;

	// withdraw가 clearAuthor로 비우는 컬럼들
	private static final Set<String> 서비스가_비우는_것 = Set.of("posts.author_id", "comments.author_id", "visa_reviews.author_id");

	@Test
	void users를_참조하는_모든_FK는_ON_DELETE가_있거나_탈퇴_때_서비스가_비운다() {
		// confdeltype: a=NO ACTION, r=RESTRICT, c=CASCADE, n=SET NULL, d=SET DEFAULT
		List<Map<String, Object>> fks = jdbc.queryForList("select c.conrelid::regclass::text as tbl, a.attname as col, "
				+ "c.confdeltype::text as del from pg_constraint c "
				+ "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey) "
				+ "where c.contype = 'f' and c.confrelid = 'public.users'::regclass");
		assertThat(fks).as("users를 참조하는 FK가 하나도 안 읽힘(쿼리 확인 필요)").isNotEmpty();

		Set<String> 처리안됨 = new TreeSet<>();
		for (Map<String, Object> fk : fks) {
			String name = fk.get("tbl") + "." + fk.get("col");
			String del = (String) fk.get("del");
			boolean dbHandles = del.equals("c") || del.equals("n") || del.equals("d");
			if (!dbHandles && !서비스가_비우는_것.contains(name)) {
				처리안됨.add(name);
			}
		}
		assertThat(처리안됨).as("탈퇴 때 처리되지 않는 users FK가 있다. UserService.withdraw에 clearAuthor를 추가하거나 ON DELETE를 지정하라: %s", 처리안됨)
				.isEmpty();
	}
}
