package com.example.demo.permit.repository;

import java.util.List;
import java.util.Optional;

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

    // 이 회원의 신청 행을 전부 지운다. 반환값 = 지운 행 수.
    // MANDATORY: 사진 이름 모으기·대기 표 적기와 반드시 한 트랜잭션이어야 하므로 바깥 트랜잭션 없이 부르면 예외
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from PermitRequest p where p.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
