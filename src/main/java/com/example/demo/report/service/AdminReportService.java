package com.example.demo.report.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.message.domain.Conversation;
import com.example.demo.message.repository.MessageRepository;
import com.example.demo.report.domain.Report;
import com.example.demo.report.domain.ReportStatus;
import com.example.demo.report.dto.AdminConversationResponse;
import com.example.demo.report.dto.AdminReportResponse;
import com.example.demo.report.repository.ReportRepository;

import lombok.RequiredArgsConstructor;

// 관리자 신고 처리 로직. 토큰의 ADMIN은 최대 15분 묵은 정보라 매번 DB로 재확인한다(AccessGuard.requireAdmin).
// 검사 순서: 관리자 재확인 401/403 -> 없는 신고 404 -> 이미 기각 409
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final ReportRepository reportRepository;
    private final MessageRepository messageRepository;
    private final AccessGuard accessGuard;

    // 상태별 신고 목록(id 오름차순). 연관은 쿼리 1번으로 같이 읽는다.
    @Transactional(readOnly = true)
    public List<AdminReportResponse> getAll(ReportStatus status, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        return reportRepository.findAllForAdmin(status).stream().map(AdminReportResponse::from).toList();
    }

    // 신고된 대화 읽기(S14-f2). 방 전체를 보여 준다(신고 시각으로 자르지 않음). 기각된 신고도 볼 수 있다(409 없음).
    // 검사 순서: 관리자 재확인 401/403 -> 없는 신고 404 -> 대화 신고가 아님(글·댓글·중고 글 신고) 404.
    // 읽기 전용이라 잠금을 잡지 않는다(쓰기 흐름과 고리가 생길 일이 없다). 쪽지를 읽음 처리하지 않는다.
    @Transactional(readOnly = true)
    public AdminConversationResponse getConversation(Long id, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        Report report = reportRepository.findDetailById(id).orElseThrow(ApiErrors::reportNotFound);
        Conversation room = report.getConversation();
        if (room == null) {
            throw ApiErrors.reportNotFound(); // 대화 신고만 이 통로가 열린다
        }
        return AdminConversationResponse.of(report.getId(), room,
                messageRepository.findAllWithSenderByConversationId(room.getId()));
    }

    // 기각: 대기 -> 기각. 조건부 UPDATE의 결과 행 수로 경쟁(동시 기각·삭제)을 가른다.
    @Transactional
    public AdminReportResponse dismiss(Long id, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        if (reportRepository.dismissIfPending(id) == 0) {
            // 0행 = 없거나(삭제 포함) 이미 기각. 존재 여부로 404/409를 가른다.
            if (!reportRepository.existsById(id)) {
                throw ApiErrors.reportNotFound();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 신고입니다.");
        }
        // 내가 기각했다. 바뀐 상태를 목록과 같은 모양으로 읽어 돌려준다(그 사이 삭제됐으면 404).
        return reportRepository.findDetailById(id).map(AdminReportResponse::from)
                .orElseThrow(ApiErrors::reportNotFound);
    }
}
