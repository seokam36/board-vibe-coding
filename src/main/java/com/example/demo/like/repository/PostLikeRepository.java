package com.example.demo.like.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.like.domain.PostLike;
import com.example.demo.post.dto.PostCount;

// 공감 DB 접근 담당
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    // 이 회원이 이 글에 이미 공감했는지
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 글의 공감 수
    long countByPostId(Long postId);

    // 공감 취소. 지운 행 수를 돌려준다(0이면 공감한 적 없음). 바로 DELETE 한 줄만 보낸다.
    // 바깥 트랜잭션(서비스) 없이 부르면 예외(MANDATORY) — CommentRepository.clearAuthor와 같은 규칙.
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from PostLike l where l.post.id = :postId and l.user.id = :userId")
    int deleteByPostIdAndUserId(@Param("postId") Long postId, @Param("userId") Long userId);

    // 글 id 목록의 공감 수를 한 번에 센다 (목록 N+1 방지). 공감이 0개인 글은 결과에 없다.
    // 빈 목록을 넘기면 in () 오류가 나므로 호출하는 쪽에서 막는다.
    @Query("select new com.example.demo.post.dto.PostCount(l.post.id, count(l)) "
            + "from PostLike l where l.post.id in :postIds group by l.post.id")
    List<PostCount> countByPostIds(@Param("postIds") Collection<Long> postIds);
}
