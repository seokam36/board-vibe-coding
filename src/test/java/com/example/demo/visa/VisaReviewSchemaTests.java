package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaResult;
import com.example.demo.visa.domain.VisaReview;
import com.example.demo.visa.domain.VisaType;
import com.example.demo.visa.repository.VisaReviewRepository;

// S11-a 완료 조건 1, 5, 6, 7: 앱 시작(Flyway V7 + ddl-auto=validate), DB 제약, 인덱스, 엔티티 매핑.
// 앱이 떴다는 것 자체가 validate 통과(엔티티와 표가 맞음)의 증거다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VisaReviewSchemaTests {

	@Autowired JdbcTemplate jdbc;
	@Autowired VisaReviewRepository repository;

	@AfterEach
	void 정리() {
		jdbc.update("DELETE FROM visa_reviews");
	}

	private long 학교id() {
		return jdbc.queryForObject("SELECT id FROM schools ORDER BY id LIMIT 1", Long.class);
	}

	private int 삽입(String visaType, String received, String result) {
		return jdbc.update("INSERT INTO visa_reviews (author_id, school_id, visa_type, application_type, received_date, result_date, result, content, created_at) "
				+ "VALUES (NULL, ?, ?, 'EXTENSION', ?::date, ?::date, 'APPROVED', '내용', now())", 학교id(), visaType, received, result);
	}

	// 완료 조건 1
	@Test
	void V7이_성공_기록으로_정확히_한_번_남고_앱이_validate를_통과해_뜬다() {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success = true", Integer.class);
		assertThat(count).isEqualTo(1);
		// 엔티티를 쓰는 저장소가 동작하면 validate까지 통과한 것
		assertThat(repository.count()).isZero();
	}

	// 완료 조건 5
	@Test
	void 결과일이_접수일보다_앞서면_ck_visa_reviews_dates가_거부한다() {
		assertThatThrownBy(() -> 삽입("D_2", "2026-05-10", "2026-05-09"))
				.hasMessageContaining("ck_visa_reviews_dates");
	}

	@Test
	void 모르는_visa_type_F_2를_넣으면_ck_visa_reviews_visa_type이_거부한다() {
		assertThatThrownBy(() -> 삽입("F_2", "2026-05-01", "2026-05-09"))
				.hasMessageContaining("ck_visa_reviews_visa_type");
	}

	@Test
	void 정상_행은_INSERT에_성공하고_결과일이_접수일과_같아도_된다() {
		assertThat(삽입("D_2", "2026-05-01", "2026-05-09")).isEqualTo(1);
		assertThat(삽입("D_10", "2026-05-01", "2026-05-01")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM visa_reviews", Integer.class)).isEqualTo(2);
	}

	@Test
	void 모르는_신청_종류와_결과도_각_CHECK_제약이_거부한다() {
		long school = 학교id();
		assertThatThrownBy(() -> jdbc.update("INSERT INTO visa_reviews (school_id, visa_type, application_type, received_date, result_date, result, created_at) "
				+ "VALUES (?, 'D_2', 'BAD', '2026-05-01', '2026-05-02', 'APPROVED', now())", school))
				.hasMessageContaining("ck_visa_reviews_application_type");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO visa_reviews (school_id, visa_type, application_type, received_date, result_date, result, created_at) "
				+ "VALUES (?, 'D_2', 'NEW', '2026-05-01', '2026-05-02', 'PENDING', now())", school))
				.hasMessageContaining("ck_visa_reviews_result");
	}

	@Test
	void 없는_학교로_넣으면_fk_visa_reviews_school이_거부한다() {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO visa_reviews (school_id, visa_type, application_type, received_date, result_date, result, created_at) "
				+ "VALUES (999999, 'D_2', 'NEW', '2026-05-01', '2026-05-02', 'APPROVED', now())"))
				.hasMessageContaining("fk_visa_reviews_school");
	}

	// 작성자 FK는 ON DELETE 동작이 없다 -> 탈퇴 전에 서비스가 author_id를 비워야 한다는 전제를 테스트로 남긴다
	@Test
	void 없는_회원으로_넣으면_fk_visa_reviews_author가_거부한다() {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO visa_reviews (author_id, school_id, visa_type, application_type, received_date, result_date, result, created_at) "
				+ "VALUES (999999, ?, 'D_2', 'NEW', '2026-05-01', '2026-05-02', 'APPROVED', now())", 학교id()))
				.hasMessageContaining("fk_visa_reviews_author");
	}

	// 완료 조건 6
	@Test
	void 인덱스_세_개가_존재한다() {
		List<String> names = jdbc.queryForList(
				"SELECT indexname FROM pg_indexes WHERE indexname IN ('idx_visa_reviews_stats','idx_visa_reviews_author_id','idx_users_verified_school_id')",
				String.class);
		assertThat(names).containsExactlyInAnyOrder("idx_visa_reviews_stats", "idx_visa_reviews_author_id",
				"idx_users_verified_school_id");
	}

	// 완료 조건 7
	@Test
	void SQL로_넣은_행을_VisaReview로_읽으면_enum과_날짜가_맞게_매핑된다() {
		jdbc.update("INSERT INTO visa_reviews (author_id, school_id, visa_type, application_type, received_date, result_date, result, content, created_at) "
				+ "VALUES (NULL, ?, 'E_7', 'CHANGE', '2026-03-01', '2026-03-20', 'DENIED', '본문', now())", 학교id());
		Long id = jdbc.queryForObject("SELECT id FROM visa_reviews", Long.class);

		VisaReview r = repository.findById(id).orElseThrow();
		assertThat(r.getVisaType()).isEqualTo(VisaType.E_7);
		assertThat(r.getApplicationType()).isEqualTo(VisaApplicationType.CHANGE);
		assertThat(r.getResult()).isEqualTo(VisaResult.DENIED);
		assertThat(r.getReceivedDate()).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(r.getResultDate()).isEqualTo(LocalDate.of(2026, 3, 20));
		assertThat(r.getContent()).isEqualTo("본문");
		assertThat(r.getAuthor()).isNull();
		assertThat(r.getCreatedAt()).isNotNull();
	}
}
