package com.example.demo.post.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.post.domain.Post;
import com.example.demo.post.dto.PostCreateRequest;
import com.example.demo.post.dto.PostResponse;
import com.example.demo.post.dto.PostUpdateRequest;
import com.example.demo.post.repository.PostRepository;

import lombok.RequiredArgsConstructor;

// 게시글 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor // final 필드 생성자 주입 자동 생성
public class PostService {

    private final PostRepository postRepository;

    // 게시글을 저장하고 저장된 결과(id, createdAt 포함)를 돌려준다
    @Transactional
    public PostResponse create(PostCreateRequest request) {
        Post saved = postRepository.save(new Post(request.title(), request.content()));
        return PostResponse.from(saved);
    }

    // 게시글 한 건 조회. 없으면 404
    @Transactional(readOnly = true)
    public PostResponse get(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));
        return PostResponse.from(post);
    }

    // 게시글 수정. 없으면 404. 영속 상태 엔티티를 바꾸면 트랜잭션 종료 시 자동으로 UPDATE 된다(dirty checking)
    @Transactional
    public PostResponse update(Long id, PostUpdateRequest request) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));
        post.update(request.title(), request.content());
        return PostResponse.from(post);
    }

    // 게시글 삭제(DB 행 제거). 없으면 404
    @Transactional
    public void delete(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));
        postRepository.delete(post);
    }

    // 전체 목록을 최신순으로 조회 (페이징 없음)
    @Transactional(readOnly = true)
    public List<PostResponse> getAll() {
        return postRepository.findAllByOrderByIdDesc().stream()
                .map(PostResponse::from)
                .toList();
    }
}
