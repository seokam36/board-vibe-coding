-- 챗봇 확인용 테스트 중고 글 넣기 (운영·로컬 공용, Flyway V 파일 아님 — 필요할 때 손으로 한 번 실행)
-- 왜: 챗봇이 실제로 글을 찾아 답하는지 여러 질문(가격 조건, 나눔, 한 글자 키워드, 0건, 판매 끝난 글 제외)으로 보기 위해.
-- 누구 글로: 운영 테스트 계정 wntjrdka(학교 인증된 계정). 학교는 그 계정의 인증 학교를 그대로 쓴다.
-- 표시: 제목이 모두 "[테스트]"로 시작한다. 지우기는 chatbot-test-cleanup.sql.
-- 실행(서버): docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < ~/chatbot-test-seed.sql

BEGIN;

-- 테스트 계정이 없거나 학교 인증이 안 돼 있으면 한 줄도 안 들어간다(아래 WHERE). 그래서 먼저 확인한다.
SELECT username, verified_school_id FROM users WHERE username = 'wntjrdka';

INSERT INTO market_items (seller_id, school_id, title, content, trade_type, price, status, created_at)
SELECT u.id, u.verified_school_id, v.title, v.content, v.trade_type, v.price, v.status, now() - v.ago
FROM users u
CROSS JOIN (VALUES
    -- 자전거 3개: "3만 원 이하 자전거" → 25,000원만, "자전거" → 셋 다(판매중·예약중)
    ('[테스트] 접이식 자전거 팝니다',      '1년 탄 접이식 자전거예요. 바퀴 상태 좋아요.',               'SALE', 25000,  'ON_SALE',  interval '1 hour'),
    ('[테스트] 로드 자전거 판매',           '기어 잘 들어가고 헬멧도 같이 드려요.',                     'SALE', 45000,  'ON_SALE',  interval '2 hours'),
    ('[테스트] 아이용 자전거 예약중',       '보조바퀴 달린 작은 자전거입니다. 주말 거래 예정.',         'SALE', 15000,  'RESERVED', interval '3 hours'),
    -- 책·책상: "전공책 나눔" → 나눔 글, "책" → 전공책과 책상·책장이 같이 걸리는 알려진 한계
    ('[테스트] 전공책 나눔해요',            '경영학원론, 회계원리 두 권 나눔합니다.',                    'FREE', 0,      'ON_SALE',  interval '4 hours'),
    ('[테스트] 원룸 책상 싸게 팔아요',      '접이식 책상, 이사 가서 급하게 팝니다.',                     'SALE', 10000,  'ON_SALE',  interval '5 hours'),
    ('[테스트] 3단 책장 나눔',              '조립식 책장이에요. 직접 가져가셔야 해요.',                 'FREE', 0,      'ON_SALE',  interval '6 hours'),
    -- 가전: "밥솥 팔아?", "5만 원 이하 가전"
    ('[테스트] 쿠쿠 밥솥 3인용',            '6개월 사용, 내솥 깨끗해요.',                                'SALE', 30000,  'ON_SALE',  interval '7 hours'),
    ('[테스트] 전기포트 나눔',              '물 끓이는 전기포트, 잘 됩니다.',                            'FREE', 0,      'ON_SALE',  interval '8 hours'),
    ('[테스트] 미니 선풍기 팝니다',         '책상용 USB 선풍기예요.',                                     'SALE', 5000,   'ON_SALE',  interval '9 hours'),
    -- 전자기기: "노트북 50만 원 이하", "모니터 있어?"
    ('[테스트] 맥북 에어 M1 판매',          '배터리 효율 90%, 충전기 포함.',                              'SALE', 550000, 'ON_SALE',  interval '10 hours'),
    ('[테스트] 24인치 모니터',              'HDMI 케이블 같이 드려요. 화면 깨끗합니다.',                 'SALE', 60000,  'ON_SALE',  interval '11 hours'),
    -- 옷·생활: "패딩 있어?", "이불 나눔"
    ('[테스트] 겨울 패딩 L 사이즈',         '한 시즌 입었어요. 세탁 완료.',                               'SALE', 40000,  'ON_SALE',  interval '12 hours'),
    ('[테스트] 이불 세트 나눔',             '싱글 이불과 베개 커버 나눔해요.',                           'FREE', 0,      'ON_SALE',  interval '13 hours'),
    ('[테스트] 운동화 260 팝니다',          '두 번 신은 러닝화예요.',                                     'SALE', 35000,  'ON_SALE',  interval '14 hours'),
    -- 거래완료 글: 챗봇은 판매중·예약중만 찾으므로 "의자 있어?"에 이 글은 안 나와야 한다(0건이면 정상)
    ('[테스트] 사무용 의자 거래완료',       '이미 팔린 의자입니다.',                                      'SALE', 20000,  'DONE',     interval '15 hours')
) AS v(title, content, trade_type, price, status, ago)
WHERE u.username = 'wntjrdka' AND u.verified_school_id IS NOT NULL;

-- 몇 개 들어갔는지 확인(15가 나와야 한다)
SELECT count(*) AS test_items FROM market_items WHERE title LIKE '[테스트]%';

COMMIT;
