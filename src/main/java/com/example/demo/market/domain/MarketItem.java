package com.example.demo.market.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.demo.school.domain.School;
import com.example.demo.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 중고거래·나눔 물건 글 한 건 (market_items 표). 올리기는 MarketItemService.create가 한다.
@Entity
@Table(name = "market_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class MarketItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 판매자. 탈퇴하면 DB가 글도 같이 지우므로(ON DELETE CASCADE) 항상 값이 있다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false, updatable = false)
    private User seller;

    // 올린 시점에 판매자가 인증한 학교를 복사해 둔다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false, updatable = false)
    private School school;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // enum 이름을 문자열로 저장한다(STRING). 순서 번호로 저장하면 enum 순서가 바뀔 때 데이터가 꼬인다.
    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false, length = 20)
    private TradeType tradeType;

    // 원 단위. 나눔은 0
    @Column(nullable = false)
    private int price;

    // updatable=false: 엔티티 저장(UPDATE)이 status를 절대 쓰지 않는다.
    // 상태는 repository의 조건부 UPDATE로만 바뀐다. (내용 수정이 옛 상태 값으로 덮어쓰는 경쟁을 막으려고)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ItemStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 올리기용 생성자. 판매자·학교는 서비스가 로그인 회원에게서 넘겨 주고, 상태는 항상 판매중으로 시작한다.
    public MarketItem(User seller, School school, String title, String content, TradeType tradeType, int price) {
        this.seller = seller;
        this.school = school;
        this.title = title;
        this.content = content;
        this.tradeType = tradeType;
        this.price = price;
        this.status = ItemStatus.ON_SALE;
    }

    // 이 글의 판매자가 해당 회원인지 (수정·삭제 권한 확인용)
    public boolean isSoldBy(Long userId) {
        return seller != null && seller.getId().equals(userId);
    }

    // 내용·상태를 바꾸는 메서드는 일부러 두지 않는다. 엔티티 값을 바꿔 저장하면 "완료 글 수정 금지"·"상태 경쟁" 조건을 건너뛰게 되므로,
    // 수정은 MarketItemRepository의 조건부 UPDATE(updateContentIfNotDone·changeStatus)로만 한다 (S9 신고 기각과 같은 방식).

    // 저장 직전에 작성 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
