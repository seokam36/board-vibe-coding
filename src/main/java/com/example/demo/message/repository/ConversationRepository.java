package com.example.demo.message.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.message.domain.Conversation;

// 대화방 DB 접근 담당
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    // 이 글 + 이 구매자의 방 (없으면 빈 값)
    Optional<Conversation> findByMarketItemIdAndBuyerId(Long marketItemId, Long buyerId);

    // 방이 없을 때만 만든다. 만들었으면 새 방 id, 이미 있으면 빈 값.
    // 일반 INSERT는 동시에 두 요청이 오면 한쪽이 UNIQUE 위반(23505)을 맞고 PostgreSQL 트랜잭션이 통째로 깨진다.
    // ON CONFLICT DO NOTHING은 위반 대신 "아무것도 안 함"으로 끝나서 트랜잭션이 멀쩡하다. 먼저 넣던 쪽이 커밋될 때까지 기다렸다가 빈 값을 받는다.
    // 글/회원 FK가 깨지면 그대로 예외(서비스가 제약 이름으로 404/401로 바꾼다).
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "insert into conversations (market_item_id, item_title, buyer_id, seller_id, created_at, last_message_at) "
            + "values (:itemId, :itemTitle, :buyerId, :sellerId, :now, :now) "
            + "on conflict on constraint uk_conversations_item_buyer do nothing returning id", nativeQuery = true)
    Optional<Long> insertIfAbsent(@Param("itemId") Long itemId, @Param("itemTitle") String itemTitle,
            @Param("buyerId") Long buyerId, @Param("sellerId") Long sellerId, @Param("now") Instant now);

    // 방의 마지막 쪽지 시각을 갱신한다(UPDATE 한 문장). 더 늦은 시각으로만 바꾼다(동시에 온 쪽지 때문에 시각이 뒤로 가지 않게).
    // 반환값 = 바뀐 행 수. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Conversation c set c.lastMessageAt = :now where c.id = :id and c.lastMessageAt < :now")
    int touchLastMessageAt(@Param("id") Long id, @Param("now") Instant now);
}
