package com.example.demo.report.service;

import java.util.List;

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
import com.example.demo.message.domain.Conversation;
import com.example.demo.message.repository.ConversationRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.report.domain.Report;
import com.example.demo.report.dto.ReportCreateRequest;
import com.example.demo.report.dto.ReportResponse;
import com.example.demo.report.repository.ReportRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

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
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
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

    // 쪽지 대화방 신고. 검사 순서: 회원 없음 401 -> 미인증 403 -> (회원 행 잠금, 그새 탈퇴면 401) -> 방 없음/참가자 아님 404 -> 중복 409
    // 같은 학교 검사는 하지 않는다(답장과 같다: 대화방은 이미 시작된 사이라 참가자이기만 하면 된다). 상대가 탈퇴한 방, 글이 지워진 방도 신고할 수 있다.
    //
    // [잠금 순서 = 신고자 회원 행 먼저] 탈퇴(UserService.withdraw)는 회원 행 FOR UPDATE를 맨 먼저 잡은 뒤 방 행(buyer_id/seller_id SET NULL)과
    // 신고 행(reporter_id SET NULL)을 건드린다. 우리가 신고 INSERT를 먼저 하면 FK 검사로 방 행을 잡은 채 회원 행을 기다리게 되어
    // "탈퇴: 회원 -> 방, 신고: 방 -> 회원" 고리(교착, 500)가 생길 수 있다. 그래서 INSERT 전에 신고자 회원 행을 FOR KEY SHARE로 먼저 잡는다
    // (쪽지 보내기·답장과 같은 규칙).
    // FK 검사 순서가 트리거 이름(OID 문자열) 순이라 환경마다 달라질 수 있어, 방 행을 먼저 잡고 회원 행을 기다리는 교착을 막으려고 회원 행을 먼저 잡는 보험이다.
    //   - 탈퇴가 먼저면: 우리는 아무것도 안 쥔 채 첫 잠금에서 기다리다가 행이 없어 401.
    //   - 우리가 먼저면: 탈퇴가 자기 첫 잠금에서 우리 커밋을 기다린다(고리 없음).
    // 상대 회원 행은 잡지 않는다: 신고 INSERT가 상대 행을 건드리지 않고(FK는 신고자·방뿐), 상대의 탈퇴는 방 행을 기다릴 뿐
    // 우리가 쥔 것(신고자 회원 행·방 행의 약한 잠금)을 필요로 하지 않아 고리가 없다. 상대가 그새 탈퇴해도 신고는 정상(201)이다.
    @Transactional
    public ReportResponse reportConversation(Long conversationId, LoginUser loginUser, ReportCreateRequest request) {
        User reporter = accessGuard.requireVerified(loginUser);
        if (userRepository.lockForKeyShare(List.of(reporter.getId())).isEmpty()) {
            throw ApiErrors.loginRequired(); // 읽은 뒤 내가 탈퇴함
        }
        Conversation room = conversationRepository.findWithParticipantsById(conversationId)
                .orElseThrow(ApiErrors::conversationNotFound);
        if (!room.hasParticipant(reporter.getId())) {
            throw ApiErrors.conversationNotFound(); // 남의 방은 관리자도 없는 방과 똑같이 404
        }
        if (reportRepository.existsByConversationIdAndReporterId(conversationId, reporter.getId())) {
            throw alreadyReported();
        }
        return save(Report.ofConversation(room, reporter, request.reason(), normalize(request.detail())));
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
            if (constraint.equals("fk_reports_conversation")) {
                throw ApiErrors.conversationNotFound();
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
