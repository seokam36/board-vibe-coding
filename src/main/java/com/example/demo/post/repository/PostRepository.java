package com.example.demo.post.repository;

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

import com.example.demo.post.domain.Post;

// 게시글 DB 접근 담당 (기본 CRUD는 JpaRepository가 제공)
// JpaSpecificationExecutor: 학교·말머리 조건을 "있을 때만" 붙여 조합하려고 쓴다 (조합마다 메서드를 만들지 않기 위함)
public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {

    // 조건(Specification) + 정렬 목록 조회. 작성자·학교를 join으로 같이 읽어 글마다 추가 쿼리가 나가는 N+1을 막는다
    // (기본 메서드를 같은 이름으로 덮어써서 EntityGraph만 붙인다)
    @Override
    @EntityGraph(attributePaths = { "author", "school" })
    List<Post> findAll(Specification<Post> spec, Sort sort);

    // 단건 조회도 작성자·학교를 같이 읽는다 (기본 findById를 같은 이름으로 덮어써서 EntityGraph만 붙인다)
    @Override
    @EntityGraph(attributePaths = { "author", "school" })
    Optional<Post> findById(Long id);

    // 회원 탈퇴 때 그 회원 글의 작성자를 비운다(글은 남기고 author_id만 null). 반환값 = 바뀐 글 수.
    // 회원 행을 지우기 전에 해야 FK(posts.author_id -> users.id) 위반이 없다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Post p set p.author = null where p.author.id = :userId")
    int clearAuthor(@Param("userId") Long userId);

    // 글을 한 문장으로 지우고 지운 행 수를 돌려준다(1이면 내가 지움, 0이면 이미 없음).
    // 엔티티를 읽고 delete()하면 동시에 다른 쪽이 먼저 지웠을 때 flush에서 예외(500)가 나서, 행 수로 판단하게 한다.
    // 댓글·공감·신고는 DB의 ON DELETE CASCADE가 같이 지운다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from Post p where p.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);
}
