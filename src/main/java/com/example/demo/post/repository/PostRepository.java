package com.example.demo.post.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.post.domain.Post;

// 게시글 DB 접근 담당 (기본 CRUD는 JpaRepository가 제공)
public interface PostRepository extends JpaRepository<Post, Long> {

    // 최신순(id 내림차순) 전체 조회. 작성자를 join으로 같이 읽어 글마다 추가 쿼리가 나가는 N+1을 막는다.
    @EntityGraph(attributePaths = "author")
    List<Post> findAllByOrderByIdDesc();

    // 단건 조회도 작성자를 같이 읽는다 (기본 findById를 같은 이름으로 덮어써서 EntityGraph만 붙인다)
    @Override
    @EntityGraph(attributePaths = "author")
    Optional<Post> findById(Long id);

    // 회원 탈퇴 때 그 회원 글의 작성자를 비운다(글은 남기고 author_id만 null). 반환값 = 바뀐 글 수.
    // 회원 행을 지우기 전에 해야 FK(posts.author_id -> users.id) 위반이 없다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Post p set p.author = null where p.author.id = :userId")
    int clearAuthor(@Param("userId") Long userId);
}
