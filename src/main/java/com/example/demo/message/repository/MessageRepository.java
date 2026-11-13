package com.example.demo.message.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.message.domain.Message;
import com.example.demo.message.dto.MessageResponse;

// 쪽지 DB 접근 담당
public interface MessageRepository extends JpaRepository<Message, Long> {

    // 방 안의 쪽지를 id 오름차순으로(id가 진짜 순서. 지금은 방 행 잠금 뒤에 시각을 재서 created_at 순서와도 같지만 id가 더 확실하다).
    // 엔티티를 읽지 않고 곧바로 응답 모양으로 가져온다. mine = 보낸 사람이 나인가(보낸 사람이 탈퇴해 NULL이면 false).
    @Query("select new com.example.demo.message.dto.MessageResponse(m.id, m.content, m.createdAt, "
            + "case when m.sender.id = :me then true else false end) "
            + "from Message m where m.conversation.id = :conversationId order by m.id")
    List<MessageResponse> findResponses(@Param("conversationId") Long conversationId, @Param("me") Long me);

    // 관리자가 신고된 대화를 읽을 때(S14-f2): 방의 쪽지 전부를 보낸 사람과 함께 id 오름차순으로 가져온다.
    // LEFT JOIN FETCH: 보낸 사람이 탈퇴해 NULL이어도 쪽지는 나와야 하고, 쪽지가 몇 통이든 쿼리는 1번(N+1 없음).
    // 읽기 전용 일반 SELECT다. read_at을 바꾸는 코드(markRead)는 이 흐름에서 부르지 않는다.
    @Query("select m from Message m left join fetch m.sender where m.conversation.id = :conversationId order by m.id")
    List<Message> findAllWithSenderByConversationId(@Param("conversationId") Long conversationId);

    // 여러 방의 "마지막 쪽지"(방마다 id가 가장 큰 것)를 쿼리 한 번에 가져온다. 방이 1개든 20개든 쿼리는 1번.
    @Query("select new com.example.demo.message.repository.LastMessageRow(m.conversation.id, m.content, m.createdAt, m.sender.id) "
            + "from Message m where m.id in "
            + "(select max(m2.id) from Message m2 where m2.conversation.id in :conversationIds group by m2.conversation.id)")
    List<LastMessageRow> findLastMessages(@Param("conversationIds") Collection<Long> conversationIds);

    // ---- 안 읽음 (S14-c) ----
    // "안 읽은 쪽지" = 상대가 보냈고(sender_id IS DISTINCT FROM 나) read_at이 비어 있는 쪽지.
    // 세기 쿼리 2개와 읽음 처리 쿼리(markRead)가 모두 이 같은 식을 쓴다. 셋 중 하나를 고치면 나머지도 같이 고칠 것.
    // NULL 주의: 보낸 사람이 탈퇴하면 sender_id가 NULL인데, 흔한 `sender_id <> :me`는 NULL과 비교하면 "알 수 없음"이라
    // 그 쪽지가 세기에서도 읽음 처리에서도 빠져 숫자가 영영 안 줄어든다. IS DISTINCT FROM은 NULL을 "나와 다름"으로 취급한다.
    // m.sender.id는 messages.sender_id 칸을 바로 쓰는 것이라 회원 표와 join하지 않는다(join하면 탈퇴한 사람 쪽지가 빠진다).

    // 내가 참가자인 모든 방의 안 읽은 쪽지 수(상단바 "쪽지 (n)"용). 없으면 0.
    @Query("select count(m) from Message m join m.conversation c "
            + "where (c.buyer.id = :me or c.seller.id = :me) and m.sender.id is distinct from :me and m.readAt is null")
    long countUnread(@Param("me") Long me);

    // 여러 방의 안 읽은 쪽지 수를 쿼리 한 번에(group by). 안 읽은 게 없는 방은 결과에 없다.
    @Query("select new com.example.demo.message.repository.UnreadCountRow(m.conversation.id, count(m)) "
            + "from Message m where m.conversation.id in :conversationIds "
            + "and m.sender.id is distinct from :me and m.readAt is null group by m.conversation.id")
    List<UnreadCountRow> countUnreadByRoom(@Param("conversationIds") Collection<Long> conversationIds,
            @Param("me") Long me);

    // 읽음 처리: 이 방에서 id <= lastMessageId 이고 상대가 보낸 쪽지 중 아직 안 읽은 것만 읽은 시각을 찍는다(UPDATE 한 문장).
    // read_at is null 조건 때문에 두 번 불러도 처음 찍힌 시각은 그대로. 내가 보낸 쪽지는 절대 안 건드린다.
    // lastMessageId가 이 방의 쪽지가 아니거나 너무 커도 where에 방 id가 있어서 이 방의 쪽지만 바뀐다.
    // 반환값 = 바뀐 행 수. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Message m set m.readAt = :now where m.conversation.id = :conversationId and m.id <= :lastMessageId "
            + "and m.sender.id is distinct from :me and m.readAt is null")
    int markRead(@Param("conversationId") Long conversationId, @Param("lastMessageId") Long lastMessageId,
            @Param("me") Long me, @Param("now") Instant now);
}
