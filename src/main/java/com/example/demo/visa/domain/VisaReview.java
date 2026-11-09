package com.example.demo.visa.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.example.demo.school.domain.School;
import com.example.demo.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 비자 후기 한 건 (visa_reviews 표). 작성은 VisaReviewService.create가 한다.
@Entity
@Table(name = "visa_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class VisaReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 작성자. 탈퇴하면 서비스가 먼저 null로 비우므로 nullable (글·댓글과 같은 방식)
    // updatable = false: 수정 때 dirty checking UPDATE가 author_id를 옛 값으로 다시 쓰면, 수정 중 탈퇴와 겹칠 때
    // FK 위반(500)이 나기 때문이다. (Post·Comment.author와 같은 이유. 탈퇴 때는 JPQL 일괄 UPDATE로 비운다)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    private User author;

    // 작성 시점에 인증한 학교를 복사해 둔다. 작성자가 탈퇴해도 남아서 학교별 통계가 유지된다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id", nullable = false, updatable = false)
    private School school;

    // enum 이름을 문자열로 저장한다(STRING). 순서 번호로 저장하면 enum 순서가 바뀔 때 데이터가 꼬인다.
    @Enumerated(EnumType.STRING)
    @Column(name = "visa_type", nullable = false, length = 20)
    private VisaType visaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_type", nullable = false, length = 20)
    private VisaApplicationType applicationType;

    // 접수일과 결과 받은 날 (DB의 CHECK가 결과일 >= 접수일을 보장)
    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "result_date", nullable = false)
    private LocalDate resultDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisaResult result;

    // 후기 본문. 비어 있으면 ''(DB 기본값과 같다)
    @Column(nullable = false, columnDefinition = "text")
    private String content = "";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 후기 작성용 생성자. 작성자·학교는 서비스가 로그인 회원에게서 넘겨 준다(요청 본문에서 받지 않는다).
    public VisaReview(User author, School school, VisaType visaType, VisaApplicationType applicationType,
            LocalDate receivedDate, LocalDate resultDate, VisaResult result, String content) {
        this.author = author;
        this.school = school;
        this.visaType = visaType;
        this.applicationType = applicationType;
        this.receivedDate = receivedDate;
        this.resultDate = resultDate;
        this.result = result;
        this.content = content;
    }

    // 후기 수정용. 내용 필드만 바꾸고 작성자·학교·작성 시각은 그대로 둔다(setter 대신 의도가 드러나는 메서드).
    public void update(VisaType visaType, VisaApplicationType applicationType, LocalDate receivedDate,
            LocalDate resultDate, VisaResult result, String content) {
        this.visaType = visaType;
        this.applicationType = applicationType;
        this.receivedDate = receivedDate;
        this.resultDate = resultDate;
        this.result = result;
        this.content = content;
    }

    // 이 후기를 userId가 쓴 게 맞는지 확인한다. 작성자가 탈퇴해 없으면 누구의 후기도 아니므로 false.
    public boolean isWrittenBy(Long userId) {
        return author != null && author.getId().equals(userId);
    }

    // 저장 직전에 작성 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
