package com.example.demo.visa.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// 통계 집계 구현. 엔티티를 읽지 않고 group by 한 번으로 계산만 DB에 시킨다(후기 수가 늘어도 응답 크기는 조합 수만큼).
public class VisaReviewStatsRepositoryImpl implements VisaReviewStatsRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<VisaReviewStatRow> aggregate(VisaReviewFilter filter) {
        // 넘긴 조건만 WHERE에 붙인다. "(:p is null or ...)" 방식은 PostgreSQL이 null 파라미터 타입을 못 정해 실패할 수 있어서 쓰지 않는다.
        // SQL 문자열에 붙는 건 고정 문구뿐이고, 값은 전부 바인딩 파라미터(:이름)로 넘긴다(SQL 주입 방지).
        StringBuilder sql = new StringBuilder("""
                select s.id, s.name, v.visa_type, v.application_type,
                       count(*), avg(v.result_date - v.received_date)
                from visa_reviews v
                join schools s on s.id = v.school_id
                where 1 = 1
                """);
        if (filter.schoolId() != null) {
            sql.append(" and v.school_id = :schoolId");
        }
        if (filter.visaType() != null) {
            sql.append(" and v.visa_type = :visaType");
        }
        if (filter.applicationType() != null) {
            sql.append(" and v.application_type = :applicationType");
        }
        // date - date 는 정수(일수)라서 avg가 numeric으로 나온다
        sql.append(" group by s.id, s.name, v.visa_type, v.application_type");

        var query = em.createNativeQuery(sql.toString());
        if (filter.schoolId() != null) {
            query.setParameter("schoolId", filter.schoolId());
        }
        if (filter.visaType() != null) {
            query.setParameter("visaType", filter.visaType().name());
        }
        if (filter.applicationType() != null) {
            query.setParameter("applicationType", filter.applicationType().name());
        }

        List<?> rows = query.getResultList();
        List<VisaReviewStatRow> result = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] c = (Object[]) row;
            result.add(new VisaReviewStatRow(
                    ((Number) c[0]).longValue(),
                    (String) c[1],
                    VisaType.valueOf((String) c[2]),
                    VisaApplicationType.valueOf((String) c[3]),
                    ((Number) c[4]).longValue(),
                    new BigDecimal(c[5].toString())));
        }
        return result;
    }
}
