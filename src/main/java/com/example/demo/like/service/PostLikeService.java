package com.example.demo.like.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.like.domain.PostLike;
import com.example.demo.like.dto.LikeResponse;
import com.example.demo.like.dto.LikedResponse;
import com.example.demo.like.repository.PostLikeRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;

import lombok.RequiredArgsConstructor;

// 공감 비즈니스 로직. 누른 사람은 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
@Service
@RequiredArgsConstructor
public class PostLikeService {

    private final PostLikeRepository postLikeRepository;
    private final PostRepository postRepository;
    private final AccessGuard accessGuard;

    // 공감 누르기. 미인증 403, 없는 글 404, 다른 학교 글 403, 이미 눌렀으면 409
    @Transactional
    public LikeResponse like(Long postId, LoginUser loginUser) {
        // 검사 순서: 회원 없음 401 -> 미인증 403 -> 없는 글 404 -> 다른 학교 글 403 -> 중복 409
        User user = accessGuard.requireVerified(loginUser);
        // 존재 확인(exists) 대신 글을 읽는다: 학교 id가 필요해서. 쿼리 수는 그대로 1번
        Post post = postRepository.findById(postId).orElseThrow(ApiErrors::postNotFound);
        accessGuard.requireSameSchool(user, post.getSchool());
        // 미리 확인해서 흔한 중복은 여기서 409로 거른다
        if (postLikeRepository.existsByPostIdAndUserId(postId, user.getId())) {
            throw alreadyLiked();
        }
        try {
            // IDENTITY라 save() 안에서 바로 INSERT가 나가 제약 위반을 여기서 잡을 수 있다
            postLikeRepository.save(new PostLike(post, user));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 확인을 같이 통과하면 DB의 UNIQUE(23505)가 막는다 -> 409
            // 확인 직후 회원 탈퇴/글 삭제가 커밋되면 FK 위반(23503) -> 제약 이름으로 구분
            if (ConstraintViolations.isUnique(e)) {
                throw alreadyLiked();
            }
            String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
            if (constraint.equals("fk_post_likes_user")) {
                throw ApiErrors.loginRequired();
            }
            if (constraint.equals("fk_post_likes_post")) {
                throw ApiErrors.postNotFound();
            }
            throw e; // 다른 제약 위반은 숨기지 않는다
        }
        return new LikeResponse(postLikeRepository.countByPostId(postId), true);
    }

    // 공감 취소. 회원이 DB에 없으면(유령 토큰) 401, 없는 글 404, 공감한 적 없으면 404
    @Transactional
    public LikeResponse unlike(Long postId, LoginUser loginUser) {
        accessGuard.requireUser(loginUser); // 탈퇴한 회원의 옛 토큰이면 401
        if (!postRepository.existsById(postId)) {
            throw ApiErrors.postNotFound();
        }
        if (postLikeRepository.deleteByPostIdAndUserId(postId, loginUser.id()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공감하지 않은 글입니다.");
        }
        return new LikeResponse(postLikeRepository.countByPostId(postId), false);
    }

    // 내가 이 글에 공감했는지. 회원이 DB에 없으면(유령 토큰) 401, 없는 글이면 404
    @Transactional(readOnly = true)
    public LikedResponse isLiked(Long postId, LoginUser loginUser) {
        accessGuard.requireUser(loginUser); // 탈퇴한 회원의 옛 토큰이면 401
        if (!postRepository.existsById(postId)) {
            throw ApiErrors.postNotFound();
        }
        return new LikedResponse(postLikeRepository.existsByPostIdAndUserId(postId, loginUser.id()));
    }

    private static ResponseStatusException alreadyLiked() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 공감한 글입니다.");
    }
}
