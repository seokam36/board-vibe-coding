-- 하는 일: 신고(reports)가 중고 글(market_items)도 대상으로 삼을 수 있게 한다 (S13-e). 기존 행·다른 표는 건드리지 않는다.

-- 신고된 중고 글. 글/댓글 신고면 NULL. 중고 글이 지워지면 신고도 DB가 같이 지운다(CASCADE).
ALTER TABLE reports ADD COLUMN market_item_id bigint;
ALTER TABLE reports ADD CONSTRAINT fk_reports_market_item
    FOREIGN KEY (market_item_id) REFERENCES market_items (id) ON DELETE CASCADE;

-- 같은 회원이 같은 중고 글을 두 번 신고하는 건 UNIQUE가 막는다.
-- 이 UNIQUE의 첫 칸(market_item_id) 인덱스가 중고 글 삭제 때 CASCADE가 "이 글의 신고"를 찾는 일도 맡는다.
ALTER TABLE reports ADD CONSTRAINT uk_reports_market_item_reporter UNIQUE (market_item_id, reporter_id);

-- 대상 규칙을 "글·댓글 중 하나"에서 "글·댓글·중고 글 셋 중 정확히 하나"로 바꾼다.
-- 기존 행은 전부 글 또는 댓글 하나만 채워져 있고 market_item_id는 NULL이라 새 규칙을 그대로 통과한다.
ALTER TABLE reports DROP CONSTRAINT ck_reports_target;
ALTER TABLE reports ADD CONSTRAINT ck_reports_target
    CHECK (num_nonnulls(post_id, comment_id, market_item_id) = 1);
