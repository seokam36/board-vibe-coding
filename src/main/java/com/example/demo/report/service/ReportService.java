package com.example.demo.report.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.report.domain.Report;
import com.example.demo.report.dto.ReportCreateRequest;
import com.example.demo.report.dto.ReportResponse;
import com.example.demo.report.repository.ReportRepository;
import com.example.demo.user.domain.User;

import lombok.RequiredArgsConstructor;

// 신고 비즈니스 로직. 신고자는 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
// 검사 순서: 회원 확인 401 -> 미인증 403 -> 대상 404 -> 다른 학교 403 -> 자기 것 400 -> 중복 409 (본문 400은 컨트롤러의 @Valid가 먼저 한다)
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final MarketItemRepository marketItemRepository;
    private final AccessGuard accessGuard;

    // 글 신고
    @Transactional
    public ReportResponse reportPost(Long postId, LoginUser loginUser, ReportCreateRequest request) {
        // 회원 없음(탈퇴) 401 -> 학교 미인증 403
        User reporter = accessGuard.requireVerified(loginUser);
        Post post = postRepository.findById(postId).orElseThrow(ApiErrors::postNotFound);
        // 글의 학교가 인증 학교와 같아야 한다(findById가 학교를 같이 읽어 둠)
        accessGuard.requireSameSchool(reporter, post.getSchool());
        if (post.isWrittenBy(reporter.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 글은 신고할 수 없습니다.");
        }
        // 미리 확인해서 흔한 중복은 여기서 409로 거른다
        if (reportRepository.existsByPostIdAndReporterId(postId, reporter.getId())) {
            throw alreadyReported();
        }
        return save(Report.ofPost(post, reporter, request.reason(), normalize(request.detail())));
    }

    // 댓글 신고
    @Transactional
    public ReportResponse reportComment(Long commentId, LoginUser loginUser, ReportCreateRequest request) {
        User reporter = accessGuard.requireVerified(loginUser);
        // 댓글의 글·학교까지 한 번에 읽는다
        Comment comment = commentRepository.findWithPostSchoolById(commentId).orElseThrow(ApiErrors::commentNotFound);
        accessGuard.requireSameSchool(reporter, comment.getPost().getSchool());
        if (comment.isWrittenBy(reporter.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 댓글은 신고할 수 없습니다.");
        }
        if (reportRepository.existsByCommentIdAndReporterId(commentId, reporter.getId())) {
            throw alreadyReported();
        }
        return save(Report.ofComment(comment, reporter, request.reason(), normalize(request.detail())));
    }

    // 중고 글 신고 (글 신고와 같은 순서: 인증 -> 대상 404 -> 같은 학교 -> 자기 글 400 -> 중복 409)
    @Transactional
    public ReportResponse reportMarketItem(Long itemId, LoginUser loginUser, ReportCreateRequest request) {
        User reporter = accessGuard.requireVerified(loginUser);
        // findById가 판매자·학교를 같이 읽어 온다(EntityGraph)
        MarketItem item = marketItemRepository.findById(itemId).orElseThrow(ApiErrors::marketItemNotFound);
        accessGuard.requireSameSchool(reporter, item.getSchool());
        if (item.isSoldBy(reporter.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 중고 글은 신고할 수 없습니다.");
        }
        if (reportRepository.existsByMarketItemIdAndReporterId(itemId, reporter.getId())) {
            throw alreadyReported();
        }
        return save(Report.ofMarketItem(item, reporter, request.reason(), normalize(request.detail())));
    }

    // 저장하고, 동시 요청·삭제와 겹쳐 생기는 DB 제약 위반을 알맞은 상태코드로 바꾼다
    private ReportResponse save(Report report) {
        try {
            // IDENTITY라 save() 안에서 바로 INSERT가 나가 제약 위반을 여기서 잡을 수 있다
            return ReportResponse.from(reportRepository.save(report));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 중복 확인을 같이 통과하면 DB의 UNIQUE(23505)가 막는다 -> 409
            if (ConstraintViolations.isUnique(e)) {
                throw alreadyReported();
            }
            // 확인 직후 대상 삭제/회원 탈퇴가 커밋되면 FK 위반(23503) -> 제약 이름으로 구분
            String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
            if (constraint.equals("fk_reports_reporter")) {
                throw ApiErrors.loginRequired();
            }
            if (constraint.equals("fk_reports_post")) {
                throw ApiErrors.postNotFound();
            }
            if (constraint.equals("fk_reports_comment")) {
                throw ApiErrors.commentNotFound();
            }
            if (constraint.equals("fk_reports_market_item")) {
                throw ApiErrors.marketItemNotFound();
            }
            throw e; // 다른 제약 위반은 숨기지 않는다
        }
    }

    // detail이 빈 문자열·공백뿐이면 "없음"(null)으로 저장한다. 내용이 있으면 그대로 둔다.
    private static String normalize(String detail) {
        return detail == null || detail.isBlank() ? null : detail;
    }

    private static ResponseStatusException alreadyReported() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 신고한 대상입니다.");
    }
}
