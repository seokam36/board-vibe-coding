package com.example.demo.message.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 여러 방의 "마지막 쪽지"(방마다 id가 가장 큰 것)를 쿼리 한 번에 가져온다. 방이 1개든 20개든 쿼리는 1번.
    @Query("select new com.example.demo.message.repository.LastMessageRow(m.conversation.id, m.content, m.createdAt, m.sender.id) "
            + "from Message m where m.id in "
            + "(select max(m2.id) from Message m2 where m2.conversation.id in :conversationIds group by m2.conversation.id)")
    List<LastMessageRow> findLastMessages(@Param("conversationIds") Collection<Long> conversationIds);
}
