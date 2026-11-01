package com.example.demo.post.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.post.domain.Post;

// 게시글 DB 접근 담당 (기본 CRUD는 JpaRepository가 제공)
public interface PostRepository extends JpaRepository<Post, Long> {

    // 최신순(id 내림차순) 전체 조회
    List<Post> findAllByOrderByIdDesc();
}
