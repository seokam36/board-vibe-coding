package com.example.demo.school.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.school.domain.SchoolNameAlias;

// 학교 이름 별칭 DB 접근 담당
public interface SchoolNameAliasRepository extends JpaRepository<SchoolNameAlias, Long> {

    // 별칭(소문자로 바꾼 글자)으로 어느 학교인지 찾는다. 정확히 일치만 찾는다(부분 일치 아님).
    // school을 같이 가져와 school.getId()를 읽을 때 지연 로딩 문제가 없게 한다.
    @EntityGraph(attributePaths = "school")
    Optional<SchoolNameAlias> findByAlias(String alias);
}
