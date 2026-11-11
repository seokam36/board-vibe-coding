package com.example.demo.market.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.market.domain.MarketItem;

// 물건 글 저장소
public interface MarketItemRepository extends JpaRepository<MarketItem, Long> {

    // 단건 조회는 판매자·학교를 join으로 같이 읽는다 (응답 변환 때 추가 쿼리가 나가지 않게)
    @Override
    @EntityGraph(attributePaths = { "seller", "school" })
    Optional<MarketItem> findById(Long id);
}
