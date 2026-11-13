-- 하는 일: 신고(reports)가 쪽지 대화방(conversations)도 대상으로 삼을 수 있게 한다 (S14-f). 기존 행·다른 표는 건드리지 않는다.
-- V10(중고 글 신고)과 같은 방식이다. users를 가리키는 FK는 새로 만들지 않는다(신고자 FK fk_reports_reporter는 V5 그대로).

-- 신고된 대화방. 글/댓글/중고 글 신고면 NULL. 대화방이 지워지면 신고도 DB가 같이 지운다(CASCADE).
ALTER TABLE reports ADD COLUMN conversation_id bigint;
ALTER TABLE reports ADD CONSTRAINT fk_reports_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE;

-- 같은 회원이 같은 대화방을 두 번 신고하는 건 UNIQUE가 막는다.
-- 이 UNIQUE의 첫 칸(conversation_id) 인덱스가 대화방 삭제 때 CASCADE가 "이 방의 신고"를 찾는 일도 맡는다.
ALTER TABLE reports ADD CONSTRAINT uk_reports_conversation_reporter UNIQUE (conversation_id, reporter_id);

-- 대상 규칙을 "글·댓글·중고 글 중 하나"에서 "글·댓글·중고 글·대화방 넷 중 정확히 하나"로 바꾼다.
-- 기존 행은 전부 앞의 셋 중 하나만 채워져 있고 conversation_id는 NULL이라 새 규칙을 그대로 통과한다.
ALTER TABLE reports DROP CONSTRAINT ck_reports_target;
ALTER TABLE reports ADD CONSTRAINT ck_reports_target
    CHECK (num_nonnulls(post_id, comment_id, market_item_id, conversation_id) = 1);
