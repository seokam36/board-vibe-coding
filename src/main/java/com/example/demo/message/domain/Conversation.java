package com.example.demo.message.domain;

import java.time.Instant;

import com.example.demo.market.domain.MarketItem;
import com.example.demo.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 대화방 한 개 (conversations 표). "중고 글 + 구매자" 한 쌍에 방 1개.
// 방 만들기는 ConversationRepository.insertIfAbsent(동시 요청에도 안전한 INSERT ... ON CONFLICT)가 하므로
// 이 엔티티는 읽기 전용이다(생성자·수정 메서드 없음). 글·회원이 지워지면 DB가 해당 칸을 NULL로 비운다(ON DELETE SET NULL).
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 대상 글. 글이 지워지면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_item_id", updatable = false)
    private MarketItem marketItem;

    // 방을 만들 때 복사해 둔 글 제목
    @Column(name = "item_title", nullable = false, length = 100, updatable = false)
    private String itemTitle;

    // 구매자. 탈퇴하면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", updatable = false)
    private User buyer;

    // 판매자. 탈퇴하면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", updatable = false)
    private User seller;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 마지막 쪽지 시각. 엔티티 저장이 옛 값으로 덮어쓰지 않게 updatable=false, 갱신은 repository의 UPDATE 한 문장으로만 한다.
    @Column(name = "last_message_at", nullable = false, updatable = false)
    private Instant lastMessageAt;

    // 이 회원이 방의 참가자(구매자 또는 판매자)인가. 탈퇴해서 비어 있는 칸은 아무도 아니다.
    public boolean hasParticipant(Long userId) {
        return (buyer != null && buyer.getId().equals(userId)) || (seller != null && seller.getId().equals(userId));
    }

    // 내(userId) 기준 상대. 상대가 탈퇴했으면 null. (호출 전에 hasParticipant로 참가자인지 먼저 확인한다)
    public User partnerOf(Long userId) {
        return (buyer != null && buyer.getId().equals(userId)) ? seller : buyer;
    }
}
