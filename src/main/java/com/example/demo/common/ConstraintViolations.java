package com.example.demo.common;

import java.sql.SQLException;
import java.util.Optional;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

// DB 제약 위반 예외에서 "어느 제약이 깨졌는지"를 꺼낸다. 서비스들이 같은 코드를 복붙하지 않게 모아 둔 도구.
public final class ConstraintViolations {

    private static final String FOREIGN_KEY = "23503"; // PostgreSQL SQLState: FK 위반
    private static final String UNIQUE = "23505"; // PostgreSQL SQLState: UNIQUE 위반

    private ConstraintViolations() {
    }

    // FK(23503)·UNIQUE(23505) 위반이면 깨진 제약의 이름, 그 밖이면 빈 값.
    // 드라이버 클래스(PSQLException)는 컴파일 때 쓸 수 없어서 메시지에서 찾는다.
    // PostgreSQL 메시지 형식: ... violates foreign key constraint "fk_comments_post" on table ...
    // 전제: DB 메시지 언어가 영어(공식 이미지 기본값)여야 한다. lc_messages를 한국어로 바꾸면
    // 'constraint "' 글자가 없어 못 찾고 서비스가 500을 낸다. (docs/deploy.md에도 적어 둠)
    public static Optional<String> violatedConstraint(DataIntegrityViolationException e) {
        SQLException sql = sqlOf(e);
        if (sql == null || !(FOREIGN_KEY.equals(sql.getSQLState()) || UNIQUE.equals(sql.getSQLState()))) {
            return Optional.empty();
        }
        String message = String.valueOf(sql.getMessage());
        int start = message.indexOf("constraint \"");
        if (start < 0) {
            return Optional.empty();
        }
        start += "constraint \"".length();
        int end = message.indexOf('"', start);
        return end < 0 ? Optional.empty() : Optional.of(message.substring(start, end));
    }

    // UNIQUE 위반(23505)인가
    public static boolean isUnique(DataIntegrityViolationException e) {
        SQLException sql = sqlOf(e);
        return sql != null && UNIQUE.equals(sql.getSQLState());
    }

    // FK 위반(23503)인가
    public static boolean isForeignKey(DataIntegrityViolationException e) {
        SQLException sql = sqlOf(e);
        return sql != null && FOREIGN_KEY.equals(sql.getSQLState());
    }

    // 예외 맨 밑 원인이 SQLException이면 돌려주고, 아니면 null
    private static SQLException sqlOf(DataIntegrityViolationException e) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql ? sql : null;
    }
}
