package com.example.demo.post.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
