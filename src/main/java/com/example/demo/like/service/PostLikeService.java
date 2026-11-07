package com.example.demo.like.service;

import java.sql.SQLException;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.like.domain.PostLike;
import com.example.demo.like.dto.LikeResponse;
import com.example.demo.like.dto.LikedResponse;
import com.example.demo.like.repository.PostLikeRepository;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

// 공감 비즈니스 로직. 누른 사람은 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
@Service
@RequiredArgsConstructor
public class PostLikeService {

    private final PostLikeRepository postLikeRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    // 공감 누르기. 없는 글 404, 이미 눌렀으면 409
    @Transactional
    public LikeResponse like(Long postId, LoginUser loginUser) {
        // 토큰은 유효한데 회원이 DB에 없으면(탈퇴) 401
        User user = userRepository.findById(loginUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        if (!postRepository.existsById(postId)) {
            throw postNotFound();
        }
        // 미리 확인해서 흔한 중복은 여기서 409로 거른다
        if (postLikeRepository.existsByPostIdAndUserId(postId, user.getId())) {
            throw alreadyLiked();
        }
        try {
            // IDENTITY라 save() 안에서 바로 INSERT가 나가 제약 위반을 여기서 잡을 수 있다
            postLikeRepository.save(new PostLike(postRepository.getReferenceById(postId), user));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 확인을 같이 통과하면 DB의 UNIQUE(23505)가 막는다 -> 409
            // 확인 직후 회원 탈퇴/글 삭제가 커밋되면 FK 위반(23503) -> 제약 이름으로 구분
            if (NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql) {
                String state = sql.getSQLState();
                String message = String.valueOf(sql.getMessage());
                if ("23505".equals(state)) {
                    throw alreadyLiked();
                }
                if ("23503".equals(state)) {
                    if (message.contains("fk_post_likes_user")) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
                    }
                    if (message.contains("fk_post_likes_post")) {
                        throw postNotFound();
                    }
                }
            }
            throw e; // 다른 제약 위반은 숨기지 않는다
        }
        return new LikeResponse(postLikeRepository.countByPostId(postId), true);
    }

    // 공감 취소. 없는 글 404, 공감한 적 없으면 404
    @Transactional
    public LikeResponse unlike(Long postId, LoginUser loginUser) {
        if (!postRepository.existsById(postId)) {
            throw postNotFound();
        }
        if (postLikeRepository.deleteByPostIdAndUserId(postId, loginUser.id()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공감하지 않은 글입니다.");
        }
        return new LikeResponse(postLikeRepository.countByPostId(postId), false);
    }

    // 내가 이 글에 공감했는지. 없는 글이면 404
    @Transactional(readOnly = true)
    public LikedResponse isLiked(Long postId, LoginUser loginUser) {
        if (!postRepository.existsById(postId)) {
            throw postNotFound();
        }
        return new LikedResponse(postLikeRepository.existsByPostIdAndUserId(postId, loginUser.id()));
    }

    private static ResponseStatusException postNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
    }

    private static ResponseStatusException alreadyLiked() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 공감한 글입니다.");
    }
}
