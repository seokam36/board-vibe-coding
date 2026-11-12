package com.example.demo.market.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.demo.market.domain.MarketItemPhoto;

// 중고 글 사진 저장소
public interface MarketItemPhotoRepository extends JpaRepository<MarketItemPhoto, Long> {

    // 한 글의 사진을 올린 순(id 오름차순)으로
    List<MarketItemPhoto> findByMarketItemIdOrderByIdAsc(Long marketItemId);

    // 사진 한 장을 "사진 번호 + 글 번호" 둘 다 맞을 때만 찾는다 (S13-g3).
    // 글 번호까지 보는 이유: 다른 글의 사진 번호로 요청해도 그 사진이 보이거나 지워지지 않게(404)
    Optional<MarketItemPhoto> findByIdAndMarketItemId(Long id, Long marketItemId);

    // 목록용 대표 사진: 주어진 글들 각각에서 id가 가장 작은 사진 한 장씩을 쿼리 1번으로 가져온다 (글마다 따로 묻는 N+1 방지).
    // 사진이 없는 글은 결과에 없다.
    @Query("select p from MarketItemPhoto p where p.id in "
            + "(select min(p2.id) from MarketItemPhoto p2 where p2.marketItemId in :itemIds group by p2.marketItemId)")
    List<MarketItemPhoto> findCoverPhotos(@Param("itemIds") Collection<Long> itemIds);

    // 한 글의 사진 수 (글당 최대 10장 검사용)
    long countByMarketItemId(Long marketItemId);
}
