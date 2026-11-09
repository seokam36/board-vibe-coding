package com.example.demo.comment.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.dto.CommentCreateRequest;
import com.example.demo.comment.dto.CommentResponse;
import com.example.demo.comment.dto.CommentUpdateRequest;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.post.domain.Post;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;

import lombok.RequiredArgsConstructor;

// 댓글 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final AccessGuard accessGuard;

    // 글의 댓글 목록(id 오름차순). 없는 글이면 404, 댓글이 없으면 빈 배열
    @Transactional(readOnly = true)
    public List<CommentResponse> getAll(Long postId) {
        if (!postRepository.existsById(postId)) {
            throw ApiErrors.postNotFound();
        }
        return commentRepository.findByPostIdOrderByIdAsc(postId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    // 댓글 작성. 작성자는 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
    @Transactional
    public CommentResponse create(Long postId, LoginUser loginUser, CommentCreateRequest request) {
        // 검사 순서: 회원 없음 401 -> 미인증 403 -> 없는 글 404 -> 다른 학교 글 403
        // (글이 있는지 모르는 채 학교 얘기부터 하지 않도록 404가 학교 403보다 먼저. 이 서비스의 기존 관례와 같다)
        User author = accessGuard.requireVerified(loginUser);
        // 존재 확인(exists) 대신 글을 읽는다: 학교 id가 필요해서. 쿼리 수는 그대로 1번
        Post post = postRepository.findById(postId).orElseThrow(ApiErrors::postNotFound);
        accessGuard.requireSameSchool(author, post.getSchool());
        try {
            // IDENTITY라 save() 안에서 바로 INSERT가 나가 FK 위반을 여기서 잡는다.
            Comment saved = commentRepository.save(
                    new Comment(post, author, request.content()));
            return CommentResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 확인 직후 회원 탈퇴/글 삭제가 커밋되면 FK 위반(SQLState 23503)이 난다. 어느 FK인지 이름으로 구분한다.
            String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
            if (constraint.equals("fk_comments_author")) {
                throw ApiErrors.loginRequired();
            }
            if (constraint.equals("fk_comments_post")) {
                throw ApiErrors.postNotFound();
            }
            throw e; // 다른 제약 위반은 숨기지 않는다
        }
    }

    // 댓글 수정. 없으면 404, 내 댓글이 아니면 403. 영속 엔티티를 바꾸면 커밋 때 자동 UPDATE(dirty checking)
    @Transactional
    public CommentResponse update(Long id, LoginUser loginUser, CommentUpdateRequest request) {
        Comment comment = findOrThrow(id);
        checkWriter(comment, loginUser);
        comment.update(request.content());
        return CommentResponse.from(comment);
    }

    // 댓글 삭제(완전 삭제). 없으면 404, 본인도 관리자도 아니면 403
    @Transactional
    public void delete(Long id, LoginUser loginUser) {
        Comment comment = findOrThrow(id);
        checkWriterOrAdmin(comment, loginUser);
        // 권한 확인 뒤 동시에 다른 쪽이 먼저 지웠으면 0행이므로 404 (예외 500 방지)
        // 이 호출 뒤에는 위에서 읽은 엔티티를 쓰지 않는다(clearAutomatically로 영속성 컨텍스트가 비워짐)
        if (commentRepository.deleteByIdReturningCount(id) == 0) {
            throw ApiErrors.commentNotFound();
        }
    }

    private Comment findOrThrow(Long id) {
        return commentRepository.findById(id)
                .orElseThrow(ApiErrors::commentNotFound);
    }

    // 남의 댓글(작성자가 없는 댓글 포함)이면 403
    private void checkWriter(Comment comment, LoginUser loginUser) {
        if (!comment.isWrittenBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 쓴 댓글만 수정할 수 있습니다.");
        }
    }

    // 삭제 권한: 본인이거나 관리자(토큰 role이 ADMIN이면 DB에서 다시 확인). 작성자가 없는 옛 댓글은 관리자만 지울 수 있다.
    private void checkWriterOrAdmin(Comment comment, LoginUser loginUser) {
        accessGuard.checkOwnerOrAdmin(comment.isWrittenBy(loginUser.id()), loginUser, "본인이 쓴 댓글만 삭제할 수 있습니다.");
    }
}
