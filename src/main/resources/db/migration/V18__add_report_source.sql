-- 하는 일: 신고(reports)에 "누가 올렸나(source)"를 더한다 (S30-b). USER = 회원이 직접, AUTO = 사진 자동 검사가 애매하다고 올린 신고.
-- 기존 행은 전부 회원 신고라 DEFAULT 'USER'로 채워진다. 기존 제약(V5·V10·V13)은 건드리지 않는다.
ALTER TABLE reports ADD COLUMN source varchar(20) NOT NULL DEFAULT 'USER';
ALTER TABLE reports ADD CONSTRAINT ck_reports_source CHECK (source IN ('USER', 'AUTO'));

-- 자동 신고는 신고자가 없고(reporter_id NULL), 중고 글에만 붙는다. 회원 신고(USER)는 이 규칙을 안 받는다.
ALTER TABLE reports ADD CONSTRAINT ck_reports_auto_shape
    CHECK (source <> 'AUTO' OR (reporter_id IS NULL AND market_item_id IS NOT NULL));

-- 같은 중고 글에 "대기 중인 자동 신고"는 1건만. 기각(DISMISSED)되면 이 제한에서 빠져 새 자동 신고가 다시 들어갈 수 있다.
CREATE UNIQUE INDEX uk_reports_market_item_auto_pending
    ON reports (market_item_id) WHERE source = 'AUTO' AND status = 'PENDING';
