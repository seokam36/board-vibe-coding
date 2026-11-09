package com.example.demo.visa.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.visa.domain.VisaReview;

// 비자 후기 저장소. 통계(집계 쿼리)는 S11-d에서 추가한다.
// JpaSpecificationExecutor: 학교·비자 종류·신청 종류 조건을 "있을 때만" 붙여 조합하려고 쓴다 (PostRepository와 같은 방식)
public interface VisaReviewRepository extends JpaRepository<VisaReview, Long>, JpaSpecificationExecutor<VisaReview> {

    // 조건(Specification) + 정렬 목록 조회. 작성자·학교를 join으로 같이 읽어 후기마다 추가 쿼리가 나가는 N+1을 막는다
    @Override
    @EntityGraph(attributePaths = { "author", "school" })
    List<VisaReview> findAll(Specification<VisaReview> spec, Sort sort);

    // 후기를 한 문장으로 지우고 지운 행 수를 돌려준다(1이면 내가 지움, 0이면 이미 없음).
    // 읽고 delete()하면 동시에 다른 쪽이 먼저 지웠을 때 flush에서 예외(500)가 나서, 행 수로 판단하게 한다.
    // 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from VisaReview v where v.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);

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
