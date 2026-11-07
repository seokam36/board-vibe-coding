package com.example.demo.school.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.school.domain.School;

// 학교 DB 접근 담당
public interface SchoolRepository extends JpaRepository<School, Long> {
}
