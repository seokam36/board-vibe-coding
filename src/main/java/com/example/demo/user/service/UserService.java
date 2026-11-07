package com.example.demo.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.service.RefreshTokenService;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.common.ApiErrors;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.dto.PasswordChangeRequest;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.dto.UserResponse;
import com.example.demo.user.dto.WithdrawRequest;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final TransactionTemplate transactionTemplate;

    // 회원가입. 아이디 또는 닉네임이 이미 있으면 409.
    // 메서드 전체에 @Transactional을 걸지 않는다: saveAndFlush()가 자기 트랜잭션에서 끝나야
    // unique 위반 예외를 여기서 잡아 409로 바꿀 수 있다.
    public UserResponse signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.username()) || userRepository.existsByNickname(request.nickname())) {
            throw duplicated();
        }
        User user = new User(request.username(), request.nickname(), passwordEncoder.encode(request.password()));
        try {
            // 확인과 저장 사이에 같은 아이디·닉네임이 먼저 들어오면 DB unique 제약이 막아 준다
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // 현재 users 테이블에서 위반될 수 있는 제약은 username·nickname unique 두 가지뿐이고,
            // 응답에서 둘을 구분하지 않으므로 어느 쪽이든 409로 변환한다.
            // 제약이 늘어나면 원인을 구분하도록 범위를 좁혀야 한다.
            throw duplicated();
        }
    }

    // 내 정보 조회: 토큰의 id로 DB에서 읽는다(닉네임은 토큰에 없다).
    // 토큰은 유효한데 회원이 DB에 없으면(삭제됨) 로그인 안 된 것과 같이 401.
    @Transactional(readOnly = true)
    public UserResponse getMe(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::from)
                .orElseThrow(ApiErrors::loginRequired);
    }

    // 닉네임 변경. 다른 사람이 쓰는 닉네임이면 409, 자기 닉네임 그대로면 변경 없이 200.
    // signup처럼 메서드 전체에 @Transactional을 걸지 않는다: UPDATE 쿼리가 자기 트랜잭션에서 끝나야
    // 동시 변경 때 나는 unique 위반 예외를 여기서 잡아 409로 바꿀 수 있다.
    public UserResponse updateNickname(Long id, String nickname) {
        User user = findLoginUser(id);
        if (user.getNickname().equals(nickname)) {
            return UserResponse.from(user);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw nicknameTaken();
        }
        // 엔티티를 통째로 저장하지 않고 닉네임 컬럼만 UPDATE한다(동시에 바뀐 비밀번호를 옛 값으로 덮어쓰는 lost update 방지).
        try {
            if (userRepository.updateNickname(id, nickname) == 0) {
                throw ApiErrors.loginRequired(); // 읽은 뒤 회원이 삭제됨
            }
            return new UserResponse(user.getId(), user.getUsername(), nickname);
        } catch (DataIntegrityViolationException e) {
            // 확인과 저장 사이에 다른 사람이 먼저 같은 닉네임을 가져간 경우(DB unique가 막아 줌).
            // 이 갱신에서 위반될 수 있는 제약은 nickname unique뿐이다.
            throw nicknameTaken();
        }
    }

    // 비밀번호 변경. 현재 비밀번호가 틀리거나 새 비밀번호가 현재와 같으면 400
    // (401로 하면 프론트가 토큰 재발급을 돌리므로 400으로 한다).
    // 순서: DB 변경(UPDATE 쿼리가 자기 트랜잭션으로 커밋) -> 그다음 Redis의 리프레시 토큰 전부 삭제.
    // - DB 변경이 실패하면 revokeAll까지 가지 않으므로 "비밀번호는 그대로인데 로그아웃만 된" 상태가 생기지 않는다.
    // - revokeAll이 실패하면(Redis 장애) 비밀번호는 이미 바뀌었고 500이 난다. 옛 리프레시 토큰이 남을 수 있어
    //   실패를 숨기지 않고 500으로 드러낸다(알림·재시도는 운영에서 다룰 몫).
    public void changePassword(Long id, PasswordChangeRequest request) {
        User user = findLoginUser(id);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다.");
        }
        // 현재 비밀번호는 위에서 맞다고 확인됐으므로, 문자열이 같으면 새 비밀번호도 현재와 같은 것이다
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }
        // 비밀번호 컬럼만 UPDATE(자체 트랜잭션으로 커밋). 통째 저장하면 동시에 바뀐 닉네임을 옛 값으로 덮어쓸 수 있다.
        // bcrypt(encode)는 DB 연결을 잡지 않은 채 돌리고, UPDATE만 짧게 트랜잭션을 쓴다.
        if (userRepository.updatePassword(id, passwordEncoder.encode(request.newPassword())) == 0) {
            throw ApiErrors.loginRequired(); // 읽은 뒤 회원이 삭제됨
        }
        refreshTokenService.revokeAll(id); // 모든 기기 로그아웃
    }

    // 회원 탈퇴. 비밀번호가 틀리면 400(401이면 프론트가 토큰 재발급을 돌리므로).
    // 순서: 회원 조회 -> 비밀번호 확인(bcrypt, 트랜잭션 밖) -> [한 트랜잭션: 회원 행 잠금 + 글·댓글 작성자 비우기 + 회원 삭제] -> Redis 토큰 전부 삭제.
    // 트랜잭션 범위는 TransactionTemplate으로 잡는다. 같은 클래스의 @Transactional 메서드를 부르면 프록시를 거치지 않아
    // 트랜잭션이 안 걸리고, 저장소 default 메서드나 별도 빈을 만들 만큼 큰 로직도 아니라서 이 방법이 가장 단순하다.
    // - 글·댓글 UPDATE와 회원 DELETE는 반드시 같은 트랜잭션: 작성자를 비우지 않고 회원을 지우면 FK 위반이고,
    //   중간에 실패하면 전부 롤백되어 "글·댓글만 비워지고 회원은 남는" 상태가 없다.
    // - bcrypt는 트랜잭션 밖에서 돌려 DB 연결(풀 3개)을 오래 잡지 않는다.
    // - revokeAll이 실패하면(Redis 장애) 회원은 이미 지워진 채 500이 난다. 남은 리프레시 토큰으로 재발급을 요청해도
    //   AuthService.refresh가 DB에서 회원을 찾지 못해 401이 되므로 탈퇴한 사람이 다시 로그인되는 일은 없다.
    public void withdraw(Long id, WithdrawRequest request) {
        User user = findLoginUser(id);
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호가 올바르지 않습니다.");
        }
        Integer deleted = transactionTemplate.execute(status -> {
            // 1) 회원 행을 FOR UPDATE로 먼저 잠근다(READ COMMITTED에서 UPDATE와 DELETE 사이에 글·댓글 INSERT가 끼는 것을 막기 위해).
            //    - 이미 글·댓글 INSERT를 한 요청이 있으면: 그 커밋이 끝날 때까지 여기서 기다리고, 그 뒤 clearAuthor가 그것까지 비운다.
            //    - 나중에 온 글·댓글·공감 INSERT는: FK 검사가 이 잠금에 막혔다가 회원 삭제 후 FK 위반이 나고, PostService/CommentService.create·PostLikeService.like가 401로 바꾼다.
    //    - 공감(post_likes)은 user_id FK가 ON DELETE CASCADE라 회원 삭제 때 DB가 같이 지운다(따로 비우지 않는다). 위 잠금이 공감 INSERT 경쟁도 막는다.
            if (userRepository.lockById(id).isEmpty()) {
                return 0; // 이미 삭제됨 -> 바깥에서 401
            }
            postRepository.clearAuthor(id); // 2) 글은 남기고 작성자만 null로
            commentRepository.clearAuthor(id); // 2-1) 댓글도 남기고 작성자만 null로
            return userRepository.deleteUserById(id); // 3) 회원 삭제
        });
        if (deleted == null || deleted == 0) {
            throw ApiErrors.loginRequired(); // 읽은 뒤 다른 요청이 먼저 탈퇴시킴
        }
        refreshTokenService.revokeAll(id); // 모든 기기 로그아웃
    }

    // 토큰은 유효한데 회원이 DB에 없으면(삭제됨) 로그인 안 된 것과 같이 401
    private User findLoginUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(ApiErrors::loginRequired);
    }

    private ResponseStatusException nicknameTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
    }

    private ResponseStatusException duplicated() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디 또는 닉네임입니다.");
    }
}
