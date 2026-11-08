package com.example.demo.post.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.like.repository.PostLikeRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.dto.PostCount;
import com.example.demo.post.dto.PostCreateRequest;
import com.example.demo.post.dto.PostResponse;
import com.example.demo.post.dto.PostUpdateRequest;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;

import lombok.RequiredArgsConstructor;

// 게시글 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor // final 필드 생성자 주입 자동 생성
public class PostService {

    private final PostRepository postRepository;
    private final SchoolRepository schoolRepository;
    private final CommentRepository commentRepository;
    private final PostLikeRepository postLikeRepository;
    private final AccessGuard accessGuard;

    // 게시글을 저장하고 저장된 결과(id, createdAt, 작성자 포함)를 돌려준다.
    // 작성자는 요청 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
    @Transactional
    public PostResponse create(LoginUser loginUser, PostCreateRequest request) {
        // 토큰은 유효한데 사용자가 DB에 없으면(탈퇴·삭제) 로그인 안 된 것과 같이 401
        User author = accessGuard.requireUser(loginUser);
        // 없는 학교 id는 잘못된 입력이므로 400 (500이 되지 않게 미리 확인한다)
        School school = schoolRepository.findById(request.schoolId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 학교입니다."));
        try {
            // id가 IDENTITY라 save() 안에서 바로 INSERT가 나가므로 FK 위반을 여기서 잡을 수 있다
            Post saved = postRepository.save(
                    new Post(request.title(), request.content(), author, school, request.category()));
            return PostResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 회원 조회 직후 탈퇴가 커밋되면 author_id FK가 위반된다(SQLState 23503).
            // author FK는 V1에서 자동 생성된 이름(fk6xvn0811tkyo3nfjk2xvqx6ns)이라 이름으로 구분하지 않고,
            // FK 위반이면 "회원이 없음"과 같이 401로 바꾼다. (학교는 위에서 미리 확인했고 삭제 API도 없다)
            // 다른 제약 위반은 숨기지 않고 그대로 던진다.
            if (!ConstraintViolations.isForeignKey(e)) {
                throw e;
            }
            throw ApiErrors.loginRequired();
        }
    }

    // 게시글 한 건 조회. 없으면 404
    @Transactional(readOnly = true)
    public PostResponse get(Long id) {
        Post post = findOrThrow(id);
        return toResponse(post);
    }

    // 글 한 건의 댓글 수·공감 수를 각각 세어 응답으로 만든다 (쿼리 2번)
    private PostResponse toResponse(Post post) {
        return PostResponse.from(post, commentRepository.countByPostId(post.getId()),
                postLikeRepository.countByPostId(post.getId()));
    }

    // 게시글 수정. 없으면 404, 내 글이 아니면 403. 영속 상태 엔티티를 바꾸면 트랜잭션 종료 시 자동으로 UPDATE 된다(dirty checking)
    @Transactional
    public PostResponse update(Long id, LoginUser loginUser, PostUpdateRequest request) {
        Post post = findOrThrow(id);
        checkWriter(post, loginUser);
        post.update(request.title(), request.content(), request.category());
        // 수정 응답에도 현재 댓글 수·공감 수를 담는다
        return toResponse(post);
    }

    // 게시글 삭제(DB 행 제거). 없으면 404, 본인도 관리자도 아니면 403
    @Transactional
    public void delete(Long id, LoginUser loginUser) {
        Post post = findOrThrow(id);
        checkWriterOrAdmin(post, loginUser);
        postRepository.delete(post);
    }

    // 목록을 최신순으로 조회 (페이징 없음). schoolId·category는 선택이며, 넘긴 조건만 AND로 건다.
    // 없는 학교면 404. 조건을 안 건 항목은 아예 WHERE에 넣지 않으므로 "school_id IS NULL"로 걸리거나
    // null 파라미터 타입 오류가 나지 않고, 학교 없는 옛 글은 학교 조건이 없을 때만 나온다.
    @Transactional(readOnly = true)
    public List<PostResponse> getAll(Long schoolId, PostCategory category) {
        if (schoolId != null && !schoolRepository.existsById(schoolId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "학교를 찾을 수 없습니다.");
        }
        Specification<Post> spec = Specification.unrestricted();
        if (schoolId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("school").get("id"), schoolId));
        }
        if (category != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        List<Post> posts = postRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));
        // 글이 없으면 집계 쿼리를 보내지 않는다 (in () 오류 방지)
        if (posts.isEmpty()) {
            return List.of();
        }
        // 댓글 수·공감 수를 글마다 세지 않고 group by로 한 번씩만 센다 (글 1 + 댓글 1 + 공감 1 = 쿼리 3번)
        List<Long> ids = posts.stream().map(Post::getId).toList();
        Map<Long, Long> commentCounts = toMap(commentRepository.countByPostIds(ids));
        Map<Long, Long> likeCounts = toMap(postLikeRepository.countByPostIds(ids));
        return posts.stream()
                .map(p -> PostResponse.from(p, commentCounts.getOrDefault(p.getId(), 0L),
                        likeCounts.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    private static Map<Long, Long> toMap(List<PostCount> counts) {
        return counts.stream().collect(Collectors.toMap(PostCount::postId, PostCount::count));
    }

    private Post findOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(ApiErrors::postNotFound);
    }

    // 남의 글(작성자가 없는 옛 글 포함)이면 403
    private void checkWriter(Post post, LoginUser loginUser) {
        if (!post.isWrittenBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 쓴 글만 수정·삭제할 수 있습니다.");
        }
    }

    // 삭제 권한: 본인이거나 관리자(토큰 role이 ADMIN이면 DB에서 다시 확인). 작성자가 없는 옛 글은 관리자만 지울 수 있다.
    private void checkWriterOrAdmin(Post post, LoginUser loginUser) {
        accessGuard.checkOwnerOrAdmin(post.isWrittenBy(loginUser.id()), loginUser, "본인이 쓴 글만 삭제할 수 있습니다.");
    }
}
