package com.example.demo.permit.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.permit.domain.PermitRequest;
import com.example.demo.permit.domain.PermitStatus;

// 허가서 신청 저장소
public interface PermitRequestRepository extends JpaRepository<PermitRequest, Long> {

    // 이 회원에게 이 상태의 신청이 있는가 (대기 중 신청 미리 확인용. 최종 보장은 DB 부분 UNIQUE)
    boolean existsByUserIdAndStatus(Long userId, PermitStatus status);

    // 가장 최근 신청 (상태 조회용)
    Optional<PermitRequest> findFirstByUserIdOrderByIdDesc(Long userId);

    // 가장 최근의 특정 상태 신청 (승인 시각 보여 주기용)
    Optional<PermitRequest> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, PermitStatus status);

    // 아직 창고에 사진이 남아 있는 신청들의 사진 이름 (회원 탈퇴·철회 때 파일을 지우려고 모은다)
    @Query("select p.photoKey from PermitRequest p where p.userId = :userId and p.photoKey is not null")
    List<String> findPhotoKeysByUserId(@Param("userId") Long userId);

    // 관리자 목록용: 상태별, 오래된 순(id 오름차순 = 대기 처리 순서), 최대 limit개 (대기 목록용)
    List<PermitRequest> findByStatusOrderByIdAsc(PermitStatus status, Limit limit);

    // 관리자 목록용: 상태별, 최신순(id 내림차순), 최대 limit개 (처리 끝난 목록용: 방금 처리한 것이 먼저 보이게)
    List<PermitRequest> findByStatusOrderByIdDesc(PermitStatus status, Limit limit);

    // 같은 번호 해시로 이 상태의 신청이 이미 있는가 (승인 전에 미리 확인해 일반 경로에서는 DB 오류가 안 나게 한다.
    // 동시 승인 경쟁의 최종 보장은 부분 UNIQUE uk_permit_requests_permit_hash)
    boolean existsByPermitNumberHashAndStatus(String permitNumberHash, PermitStatus status);

    // 기한 만료 후보(S20-c): 이 상태이고 cutoff보다 먼저 만들어진 신청의 id, id가 lastId보다 큰 것만, 오래된 순, 최대 limit개.
    // lastId로 앞 묶음 다음부터 이어 읽는다(실패한 건이 다시 뽑혀 줄 앞을 막지 않게). 잠금 없음(처리 때 다시 확인한다)
    @Query("select p.id from PermitRequest p where p.status = :status and p.createdAt < :cutoff and p.id > :lastId order by p.id asc")
    List<Long> findIdsByStatusAndCreatedAtBefore(@Param("status") PermitStatus status, @Param("cutoff") Instant cutoff,
            @Param("lastId") long lastId, Limit limit);

    // 신청자 id만 잠금 없이 읽는다 (승인·반려가 "회원 행 먼저 잠그기" 위해 잠금 전에 알아내는 용도)
    @Query("select p.userId from PermitRequest p where p.id = :id")
    Optional<Long> findUserIdById(@Param("id") Long id);

    // 신청 행을 FOR UPDATE로 잠근다(없으면 빈 값). 반드시 회원 행 잠금 "뒤에" 부른다(철회·탈퇴와 같은 순서, 아니면 40P01 교착).
    // 행 자체는 잠근 뒤에 findById로 읽어 최신 상태를 본다.
    @Query(value = "select id from permit_requests where id = :id for update", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> lockById(@Param("id") Long id);

    // 이 회원의 신청 행을 전부 지운다. 반환값 = 지운 행 수.
    // MANDATORY: 사진 이름 모으기·대기 표 적기와 반드시 한 트랜잭션이어야 하므로 바깥 트랜잭션 없이 부르면 예외
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from PermitRequest p where p.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
