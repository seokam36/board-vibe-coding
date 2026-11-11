package com.example.demo.market.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.market.domain.MarketItemPhoto;

// 중고 글 사진 저장소
public interface MarketItemPhotoRepository extends JpaRepository<MarketItemPhoto, Long> {

    // 한 글의 사진을 올린 순(id 오름차순)으로
    List<MarketItemPhoto> findByMarketItemIdOrderByIdAsc(Long marketItemId);

    // 사진 한 장을 "사진 번호 + 글 번호" 둘 다 맞을 때만 찾는다 (S13-g3).
    // 글 번호까지 보는 이유: 다른 글의 사진 번호로 요청해도 그 사진이 보이거나 지워지지 않게(404)
    Optional<MarketItemPhoto> findByIdAndMarketItemId(Long id, Long marketItemId);

    // 한 글의 사진 수 (글당 최대 10장 검사용)
    long countByMarketItemId(Long marketItemId);
}
