package com.example.demo.school.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 학교 한 곳을 나타내는 엔티티 (schools 테이블). 학교 목록은 V2 마이그레이션이 넣어 두며 앱에서는 읽기만 한다.
@Entity
@Table(name = "schools")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;
}
