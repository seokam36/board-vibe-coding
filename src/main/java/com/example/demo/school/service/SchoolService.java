package com.example.demo.school.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.school.domain.School;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.school.repository.SchoolRepository;

import lombok.RequiredArgsConstructor;

// 학교 비즈니스 로직. 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class SchoolService {

    private final SchoolRepository schoolRepository;

    // 학교 전체를 이름순으로 돌려준다
    // DB 정렬은 DB 언어 설정(collation)에 따라 한글 순서가 달라질 수 있어서, 자바에서 정렬한다(유니코드 순 = 가나다순)
    @Transactional(readOnly = true)
    public List<SchoolResponse> getAll() {
        return schoolRepository.findAll().stream()
                .sorted(Comparator.comparing(School::getName))
                .map(SchoolResponse::from)
                .toList();
    }
}
