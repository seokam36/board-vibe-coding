package com.example.demo.school.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.school.domain.SchoolEmailDomain;

// 학교 메일 도메인 DB 접근 담당
public interface SchoolEmailDomainRepository extends JpaRepository<SchoolEmailDomain, Long> {

    // 메일의 @ 뒤 도메인으로 어느 학교인지 찾는다 (정확히 일치만)
    // school을 같이 가져온다: 트랜잭션 밖(서비스)에서 school.getId()를 읽어도 지연 로딩 문제가 없게
    @EntityGraph(attributePaths = "school")
    Optional<SchoolEmailDomain> findByDomain(String domain);

    // 학교 목록 응답용: 도메인 전체를 한 번에 읽는다(학교마다 따로 조회하면 N+1이 되므로). school을 같이 가져와 지연 로딩을 피한다.
    @EntityGraph(attributePaths = "school")
    List<SchoolEmailDomain> findAllByOrderByDomainAsc();
}
