package com.example.demo.market.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.market.domain.MarketItemPhoto;

// 중고 글 사진 저장소
public interface MarketItemPhotoRepository extends JpaRepository<MarketItemPhoto, Long> {

    // 한 글의 사진을 올린 순(id 오름차순)으로
    List<MarketItemPhoto> findByMarketItemIdOrderByIdAsc(Long marketItemId);

    // 한 글의 사진 수 (글당 최대 10장 검사용)
    long countByMarketItemId(Long marketItemId);
}
