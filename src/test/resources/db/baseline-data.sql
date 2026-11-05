-- 운영 첫 배포 상황 재현용 데이터. 표는 V1__init.sql(db/migration)을 그대로 먼저 실행해서 만든다.
-- (V1은 배포 후 수정 금지이므로 표 구조를 복사해 둘 필요가 없다.)
-- 주의: Flyway 기록장(flyway_schema_history)은 만들지 않는다. 이 파일도 db/migration 밖에 둔다.

-- 기존 운영 데이터 흉내: 회원 1명 + 그 회원의 글 1개
INSERT INTO users (created_at, password, username, nickname)
VALUES (now(), 'hashed-password', 'olduser', 'oldnick');

INSERT INTO posts (content, created_at, title, author_id)
VALUES ('기존 본문', now(), '기존 제목', (SELECT id FROM users WHERE username = 'olduser'));
