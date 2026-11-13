package com.example.demo.report.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.report.domain.Report;
import com.example.demo.report.domain.ReportStatus;

// 신고 DB 접근 담당
public interface ReportRepository extends JpaRepository<Report, Long> {

    // 이 회원이 이 글을 이미 신고했는지
    boolean existsByPostIdAndReporterId(Long postId, Long reporterId);

    // 이 회원이 이 댓글을 이미 신고했는지
    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    // 이 회원이 이 중고 글을 이미 신고했는지
    boolean existsByMarketItemIdAndReporterId(Long marketItemId, Long reporterId);

    // 이 회원이 이 쪽지 대화방을 이미 신고했는지
    boolean existsByConversationIdAndReporterId(Long conversationId, Long reporterId);

    // 관리자 목록용: 화면에 필요한 연관(글·댓글·중고 글·판매자·대화방·참가자·댓글의 글·신고자·대상 작성자)을 한 번의 쿼리로 같이 읽는다(N+1 방지).
    // 모두 nullable(글 신고면 comment·marketItem·conversation 없음, 탈퇴하면 reporter·author·buyer·seller 없음)이라 반드시 LEFT JOIN.
    // 쿼리 수는 신고 수와 상관없이 1번.
    @Query("select r from Report r "
            + "left join fetch r.post p left join fetch p.author "
            + "left join fetch r.comment c left join fetch c.post left join fetch c.author "
            + "left join fetch r.marketItem m left join fetch m.seller "
            + "left join fetch r.conversation cv left join fetch cv.buyer left join fetch cv.seller "
            + "left join fetch r.reporter "
            + "where r.status = :status order by r.id asc")
    List<Report> findAllForAdmin(@Param("status") ReportStatus status);

    // 신고 한 건을 목록과 같은 모양으로 읽는다(기각 응답용)
    @Query("select r from Report r "
            + "left join fetch r.post p left join fetch p.author "
            + "left join fetch r.comment c left join fetch c.post left join fetch c.author "
            + "left join fetch r.marketItem m left join fetch m.seller "
            + "left join fetch r.conversation cv left join fetch cv.buyer left join fetch cv.seller "
            + "left join fetch r.reporter "
            + "where r.id = :id")
    Optional<Report> findDetailById(@Param("id") Long id);

    // 대기 중일 때만 기각으로 바꾸는 조건부 UPDATE. 반환값 = 바뀐 행 수(1이면 내가 기각, 0이면 없거나 이미 기각).
    // "읽고 -> 바꾸고 -> 저장"이 아니라 DB가 한 문장으로 판단해서, 두 관리자가 동시에 눌러도 한 명만 1을 받는다.
    // 상태 변경은 이 메서드 하나로만 한다(엔티티에 dismiss()를 두지 않는 이유: 길이 둘이면 경쟁 안전성이 깨지기 쉽다).
    // flushAutomatically: UPDATE 전에 앞서 바꾼 엔티티를 DB에 먼저 반영한다(clear로 버려지지 않게).
    // clearAutomatically: 영속성 컨텍스트에 남은 옛 상태를 비워 이후 조회가 새 값을 읽게 한다.
    // 바깥 트랜잭션(서비스) 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Report r set r.status = com.example.demo.report.domain.ReportStatus.DISMISSED "
            + "where r.id = :id and r.status = com.example.demo.report.domain.ReportStatus.PENDING")
    int dismissIfPending(@Param("id") Long id);
}
