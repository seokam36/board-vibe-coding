package com.example.demo.visa.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.visa.domain.VisaReview;

// 비자 후기 저장소. 목록·통계는 이후 단계에서 추가한다.
public interface VisaReviewRepository extends JpaRepository<VisaReview, Long> {

    // 단건 조회는 작성자·학교를 같이 읽는다 (PostRepository.findById와 같은 방식)
    @Override
    @EntityGraph(attributePaths = { "author", "school" })
    Optional<VisaReview> findById(Long id);

    // 회원 탈퇴 때 그 회원 후기의 작성자를 비운다(후기·학교는 남기고 author_id만 null). 반환값 = 바뀐 후기 수.
    // 회원 행을 지우기 전에 해야 FK(visa_reviews.author_id -> users.id) 위반이 없다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update VisaReview v set v.author = null where v.author.id = :userId")
    int clearAuthor(@Param("userId") Long userId);
}
