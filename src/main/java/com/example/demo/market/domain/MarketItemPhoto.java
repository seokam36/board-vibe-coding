package com.example.demo.market.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 중고 글에 올린 사진 한 장 (market_item_photos 표). 사진 파일 자체는 창고(PhotoStorage)에 있고, 여기엔 창고 이름(key)과 크기만 둔다.
// 글 객체 대신 글 id만 들고 있다: 사진을 읽을 때마다 글을 같이 읽어 올 필요가 없어서.
@Entity
@Table(name = "market_item_photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class MarketItemPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_item_id", nullable = false, updatable = false)
    private Long marketItemId;

    // 창고 이름. 서버 안에서만 쓰고 응답(DTO)에 내보내지 않는다.
    @Column(name = "storage_key", nullable = false, updatable = false, length = 255)
    private String storageKey;

    @Column(name = "thumb_key", nullable = false, updatable = false, length = 255)
    private String thumbKey;

    // image/jpeg 또는 image/png. 사진 종류는 이 값만 믿는다.
    @Column(name = "content_type", nullable = false, updatable = false, length = 20)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private int width;

    @Column(nullable = false, updatable = false)
    private int height;

    @Column(name = "thumb_width", nullable = false, updatable = false)
    private int thumbWidth;

    @Column(name = "thumb_height", nullable = false, updatable = false)
    private int thumbHeight;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MarketItemPhoto(Long marketItemId, String storageKey, String thumbKey, String contentType, int width,
            int height, int thumbWidth, int thumbHeight) {
        this.marketItemId = marketItemId;
        this.storageKey = storageKey;
        this.thumbKey = thumbKey;
        this.contentType = contentType;
        this.width = width;
        this.height = height;
        this.thumbWidth = thumbWidth;
        this.thumbHeight = thumbHeight;
    }

    // 저장 직전에 올린 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
