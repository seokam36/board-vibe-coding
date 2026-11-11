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
    @Column(name = "trade_type", nullable = false, length = 20, updatable = false)
    private TradeType tradeType;

    // 원 단위. 나눔은 0
    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
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

    // 저장 직전에 작성 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
