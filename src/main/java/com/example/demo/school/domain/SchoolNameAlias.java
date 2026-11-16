package com.example.demo.school.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 허가서에 적힌 학교 이름(영문·약칭 포함) 한 개와 우리 학교의 연결 (school_name_aliases 표). V14가 값을 넣어 두며 앱에서는 읽기만 한다.
// alias는 항상 소문자로 저장돼 있다(DB CHECK). 비교할 때 읽은 글자도 소문자로 바꿔 정확히 일치만 인정한다.
@Entity
@Table(name = "school_name_aliases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class SchoolNameAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @Column(nullable = false, length = 100)
    private String alias;
}
