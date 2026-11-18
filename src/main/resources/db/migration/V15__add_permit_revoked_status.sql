-- 하는 일: 허가서 신청 상태에 REVOKED(관리자가 승인된 인증을 취소함)를 추가한다 (S21-a).
-- 상태 CHECK(ck_permit_requests_status)만 이름 그대로 지우고 REVOKED를 포함해 다시 만든다. V14는 고치지 않는다.
-- 취소 사유는 기존 reject_reason 칸에 저장한다. ck_permit_requests_reason은 상태와 엮여 있지 않고(값 목록만 검사) 칸이 NULL이어도 되므로 그대로 둔다.
-- 새 칸·새 인덱스는 없다. 기존 행은 모두 PENDING/APPROVED/REJECTED라 다시 만든 CHECK를 그대로 통과한다.
ALTER TABLE permit_requests DROP CONSTRAINT ck_permit_requests_status;
ALTER TABLE permit_requests ADD CONSTRAINT ck_permit_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'));
