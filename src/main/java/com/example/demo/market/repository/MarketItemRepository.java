package com.example.demo.market.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;

import jakarta.persistence.LockModeType;

// 물건 글 저장소. JpaSpecificationExecutor: 학교·거래 방식·상태 조건을 "있을 때만" 붙여 조합하려고 쓴다.
public interface MarketItemRepository extends JpaRepository<MarketItem, Long>, JpaSpecificationExecutor<MarketItem> {

    // 단건 조회는 판매자·학교를 join으로 같이 읽는다 (응답 변환 때 추가 쿼리가 나가지 않게)
    @Override
    @EntityGraph(attributePaths = { "seller", "school" })
    Optional<MarketItem> findById(Long id);

    // 사진 올리기용: 글 행을 잠그고 읽는다(PESSIMISTIC_WRITE. PostgreSQL에서 Hibernate는 FOR UPDATE 또는 FOR NO KEY UPDATE로 낸다 --
    // 둘 다 다른 요청의 UPDATE·DELETE·같은 잠금과는 충돌한다). 같은 글에 사진을 동시에 올리는 요청, 상태 바꾸기(DONE)·삭제와
    // 줄을 세워서 "사진 수 세기 -> 넣기"가 둘 사이에 끼어들지 못하게 한다. 잠금은 트랜잭션이 끝날 때 풀리므로 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    // EntityGraph(join)를 안 쓴다: 잠금 쿼리에 join이 붙으면 PostgreSQL이 거절하거나 다른 표 행까지 잠글 수 있다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select m from MarketItem m where m.id = :id")
    Optional<MarketItem> findByIdForUpdate(@Param("id") Long id);

    // 조건 + 정렬 목록 조회. 판매자·학교를 join으로 같이 읽어 글마다 추가 쿼리가 나가는 N+1을 막는다
    @Override
    @EntityGraph(attributePaths = { "seller", "school" })
    List<MarketItem> findAll(Specification<MarketItem> spec, Sort sort);

    // 상태 바꾸기: "지금 상태가 from일 때만" to로 바꾸는 한 문장짜리 UPDATE. 바뀐 행 수를 돌려준다(1=내가 바꿈, 0=없거나 그새 상태가 바뀜).
    // 두 요청이 동시에 와도 DB 행 잠금 때문에 하나만 1을 받는다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update MarketItem m set m.status = :to where m.id = :id and m.status = :from")
    int changeStatus(@Param("id") Long id, @Param("from") ItemStatus from, @Param("to") ItemStatus to);

    // 내용 수정: "거래완료가 아닐 때만" 제목·본문·거래 방식·가격을 바꾸는 한 문장짜리 UPDATE. status 칸은 건드리지 않는다.
    // 읽은 뒤 다른 요청이 DONE으로 바꿔 커밋했으면 0행이 되어 내용이 안 바뀐다. 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update MarketItem m set m.title = :title, m.content = :content, m.tradeType = :tradeType, m.price = :price "
            + "where m.id = :id and m.status <> com.example.demo.market.domain.ItemStatus.DONE")
    int updateContentIfNotDone(@Param("id") Long id, @Param("title") String title, @Param("content") String content,
            @Param("tradeType") TradeType tradeType, @Param("price") int price);

    // 글을 한 문장으로 지우고 지운 행 수를 돌려준다(1이면 내가 지움, 0이면 이미 없음).
    // 읽고 delete()하면 동시에 다른 쪽이 먼저 지웠을 때 예외(500)가 나서, 행 수로 판단하게 한다.
    // 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from MarketItem m where m.id = :id")
    int deleteByIdReturningCount(@Param("id") Long id);
}
