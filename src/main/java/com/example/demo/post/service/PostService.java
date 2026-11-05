package com.example.demo.post.service;

import java.util.List;

import java.sql.SQLException;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.post.domain.Post;
import com.example.demo.post.dto.PostCreateRequest;
import com.example.demo.post.dto.PostResponse;
import com.example.demo.post.dto.PostUpdateRequest;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

// 게시글 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor // final 필드 생성자 주입 자동 생성
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;

    // 게시글을 저장하고 저장된 결과(id, createdAt, 작성자 포함)를 돌려준다.
    // 작성자는 요청 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
    @Transactional
    public PostResponse create(LoginUser loginUser, PostCreateRequest request) {
        // 토큰은 유효한데 사용자가 DB에 없으면(탈퇴·삭제) 로그인 안 된 것과 같이 401
        User author = userRepository.findById(loginUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        try {
            // id가 IDENTITY라 save() 안에서 바로 INSERT가 나가므로 FK 위반을 여기서 잡을 수 있다
            Post saved = postRepository.save(new Post(request.title(), request.content(), author));
            return PostResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 회원 조회 직후 탈퇴가 커밋되면 author_id FK가 위반된다(PostgreSQL SQLState 23503).
            // FK 위반일 때만 "회원이 없음"과 같이 401로 바꾸고, 다른 제약 위반은 숨기지 않고 그대로 던진다.
            if (!(NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql) || !"23503".equals(sql.getSQLState())) {
                throw e;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
    }

    // 게시글 한 건 조회. 없으면 404
    @Transactional(readOnly = true)
    public PostResponse get(Long id) {
        return PostResponse.from(findOrThrow(id));
    }

    // 게시글 수정. 없으면 404, 내 글이 아니면 403. 영속 상태 엔티티를 바꾸면 트랜잭션 종료 시 자동으로 UPDATE 된다(dirty checking)
    @Transactional
    public PostResponse update(Long id, LoginUser loginUser, PostUpdateRequest request) {
        Post post = findOrThrow(id);
        checkWriter(post, loginUser);
        post.update(request.title(), request.content());
        return PostResponse.from(post);
    }

    // 게시글 삭제(DB 행 제거). 없으면 404, 내 글이 아니면 403
    @Transactional
    public void delete(Long id, LoginUser loginUser) {
        Post post = findOrThrow(id);
        checkWriter(post, loginUser);
        postRepository.delete(post);
    }

    // 전체 목록을 최신순으로 조회 (페이징 없음)
    @Transactional(readOnly = true)
    public List<PostResponse> getAll() {
        return postRepository.findAllByOrderByIdDesc().stream()
                .map(PostResponse::from)
                .toList();
    }

    private Post findOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    }

    // 남의 글(작성자가 없는 옛 글 포함)이면 403
    private void checkWriter(Post post, LoginUser loginUser) {
        if (!post.isWrittenBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 쓴 글만 수정·삭제할 수 있습니다.");
        }
    }
}
