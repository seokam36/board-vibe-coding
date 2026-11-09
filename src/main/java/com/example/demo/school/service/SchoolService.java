package com.example.demo.school.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.school.domain.School;
import com.example.demo.school.domain.SchoolEmailDomain;
import com.example.demo.school.dto.SchoolDetailResponse;
import com.example.demo.school.repository.SchoolEmailDomainRepository;
import com.example.demo.school.repository.SchoolRepository;

import lombok.RequiredArgsConstructor;

// 학교 비즈니스 로직. 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class SchoolService {

    private final SchoolRepository schoolRepository;
    private final SchoolEmailDomainRepository schoolEmailDomainRepository;

    // 학교 전체를 이름순으로, 학교별 메일 도메인(정렬됨)과 함께 돌려준다.
    // 쿼리는 학교 1번 + 도메인 1번으로 고정(학교 수에 비례하지 않는다).
    // 정렬은 DB 언어 설정(collation)에 따라 한글 순서가 달라질 수 있어서 자바에서 한다(유니코드 순 = 가나다순).
    @Transactional(readOnly = true)
    public List<SchoolDetailResponse> getAll() {
        Map<Long, List<String>> domainsBySchool = schoolEmailDomainRepository.findAllByOrderByDomainAsc().stream()
                .collect(Collectors.groupingBy(d -> d.getSchool().getId(),
                        Collectors.mapping(SchoolEmailDomain::getDomain, Collectors.toList())));
        return schoolRepository.findAll().stream()
                .sorted(Comparator.comparing(School::getName))
                .map(s -> SchoolDetailResponse.of(s, domainsBySchool.getOrDefault(s.getId(), List.of()).stream().sorted().toList()))
                .toList();
    }
}
