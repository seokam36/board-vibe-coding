package com.example.demo.message.repository;

import java.time.Instant;
import java.util.List;
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

    // 방 한 개 + 구매자·판매자(닉네임용)를 한 번에 읽는다. 없으면 빈 값. (LEFT JOIN: 탈퇴해 NULL이어도 방은 나온다)
    @Query("select c from Conversation c left join fetch c.buyer left join fetch c.seller where c.id = :id")
    Optional<Conversation> findWithParticipantsById(@Param("id") Long id);

    // 내가 구매자 또는 판매자인 방을 최근 쪽지 순으로(같으면 id 내림차순). 상대 닉네임까지 쿼리 한 번에 가져와 N+1이 없다.
    @Query("select c from Conversation c left join fetch c.buyer left join fetch c.seller "
            + "where c.buyer.id = :userId or c.seller.id = :userId order by c.lastMessageAt desc, c.id desc")
    List<Conversation> findAllOfUser(@Param("userId") Long userId);

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

    // 방 행을 FOR NO KEY UPDATE로 먼저 잡는다(없으면 빈 값). 쪽지를 넣기 직전에 부른다(S14-b 리뷰 권장 반영).
    // 이유: 쪽지 INSERT의 FK 검사가 방 행에 약한 잠금(KEY SHARE)을 잡고, 그 뒤 last_message_at UPDATE가 강한 잠금을 원한다.
    // 그 사이에 글 삭제(market_item_id SET NULL = UNIQUE 칸 변경 = 가장 센 잠금)가 줄에 끼면 "삭제 <-> 답장" 고리(교착, 500)가 생긴다.
    // 처음부터 NO KEY UPDATE로 잡으면 방 행에서 한 줄로 서므로 고리가 없다. 답장끼리도 여기서 차례대로 선다(쪽지 시각·id 순서도 맞는다).
    @Query(value = "select id from conversations where id = :id for no key update", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> lockForNoKeyUpdate(@Param("id") Long id);

    // 방의 마지막 쪽지 시각을 갱신한다(UPDATE 한 문장). 더 늦은 시각으로만 바꾼다(동시에 온 쪽지 때문에 시각이 뒤로 가지 않게).
    // 반환값 = 바뀐 행 수. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update Conversation c set c.lastMessageAt = :now where c.id = :id and c.lastMessageAt < :now")
    int touchLastMessageAt(@Param("id") Long id, @Param("now") Instant now);
}
