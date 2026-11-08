-- 회원 역할(USER/ADMIN) 칸 추가. 기존 회원은 DEFAULT 때문에 모두 USER가 된다.
ALTER TABLE users ADD COLUMN role varchar(20) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
-- 관리자 지정은 API 없이 DB에서만 한다: UPDATE users SET role = 'ADMIN' WHERE username = '...';
