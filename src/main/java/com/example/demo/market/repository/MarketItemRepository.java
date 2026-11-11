package com.example.demo.market.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.market.domain.MarketItem;

// 물건 글 저장소. JpaSpecificationExecutor: 학교·거래 방식·상태 조건을 "있을 때만" 붙여 조합하려고 쓴다.
public interface MarketItemRepository extends JpaRepository<MarketItem, Long>, JpaSpecificationExecutor<MarketItem> {

    // 단건 조회는 판매자·학교를 join으로 같이 읽는다 (응답 변환 때 추가 쿼리가 나가지 않게)
    @Override
    @EntityGraph(attributePaths = { "seller", "school" })
    Optional<MarketItem> findById(Long id);

    // 조건 + 정렬 목록 조회. 판매자·학교를 join으로 같이 읽어 글마다 추가 쿼리가 나가는 N+1을 막는다
    @Override
    @EntityGraph(attributePaths = { "seller", "school" })
    List<MarketItem> findAll(Specification<MarketItem> spec, Sort sort);

    // 글을 한 문장으로 지우고 지운 행 수를 돌려준다(1이면 내가 지움, 0이면 이미 없음).
    // 읽고 delete()하면 동시에 다른 쪽이 먼저 지웠을 때 예외(500)가 나서, 행 수로 판단하게 한다.
    // 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from MarketItem m where m.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);
}
