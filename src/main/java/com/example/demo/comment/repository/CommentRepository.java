package com.example.demo.comment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.comment.domain.Comment;
import com.example.demo.post.dto.PostCount;

// 댓글 DB 접근 담당
public interface CommentRepository extends JpaRepository<Comment, Long> {

    // 글의 댓글을 오래된 순(id 오름차순)으로. 작성자를 join으로 같이 읽어 N+1을 막는다
    @EntityGraph(attributePaths = "author")
    List<Comment> findByPostIdOrderByIdAsc(Long postId);

    // 단건 조회도 작성자를 같이 읽는다 (기본 findById를 덮어써서 EntityGraph만 붙인다)
    @Override
    @EntityGraph(attributePaths = "author")
    Optional<Comment> findById(Long id);

    // 글의 댓글 수
    long countByPostId(Long postId);

    // 글 id 목록의 댓글 수를 한 번에 센다 (목록 N+1 방지). 댓글이 0개인 글은 결과에 없다.
    // 빈 목록을 넘기면 in () 오류가 나므로 호출하는 쪽에서 막는다.
    @Query("select new com.example.demo.post.dto.PostCount(c.post.id, count(c)) "
            + "from Comment c where c.post.id in :postIds group by c.post.id")
    List<PostCount> countByPostIds(@Param("postIds") Collection<Long> postIds);

    // 회원 탈퇴 때 그 회원 댓글의 작성자를 비운다(댓글은 남기고 author_id만 null). 반환값 = 바뀐 댓글 수.
    // 회원 행을 지우기 전에 해야 FK 위반이 없다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Comment c set c.author = null where c.author.id = :userId")
    int clearAuthor(@Param("userId") Long userId);

    // 댓글을 한 문장으로 지우고 지운 행 수를 돌려준다(1이면 내가 지움, 0이면 이미 없음).
    // 동시에 다른 쪽이 먼저 지워도 예외(500) 대신 0을 받아 404로 바꿀 수 있다. 공감 등은 DB의 ON DELETE CASCADE가 처리.
    // 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from Comment c where c.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);
}
