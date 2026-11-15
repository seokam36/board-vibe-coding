-- 챗봇 확인용 테스트 중고 글 지우기 (chatbot-test-seed.sql로 넣은 것만)
-- 조건: 테스트 계정 wntjrdka의 글 중 제목이 "[테스트]"로 시작하는 것. 다른 회원 글은 건드리지 않는다.
-- 실행(서버): docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < ~/chatbot-test-cleanup.sql
-- 테스트 글에 사진·쪽지·신고를 붙였다면 그것들이 이 글을 가리켜 삭제가 막힐 수 있다 → 화면에서 먼저 지우거나 알려 주기.

BEGIN;

DELETE FROM market_items
WHERE title LIKE '[테스트]%'
  AND seller_id = (SELECT id FROM users WHERE username = 'wntjrdka');

-- 남은 테스트 글 수(0이 나와야 한다)
SELECT count(*) AS test_items_left FROM market_items WHERE title LIKE '[테스트]%';

COMMIT;
