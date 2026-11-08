package com.example.demo.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.common.ApiErrors;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserRole;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

// 토큰이 말하는 "누구인지·무슨 역할인지"를 DB로 다시 확인하는 공용 도우미.
// 토큰은 최대 15분 묵은 정보라(탈퇴·관리자 해제가 바로 반영되지 않음), 중요한 판단은 DB를 한 번 더 본다.
// 호출하는 서비스의 트랜잭션(readOnly 포함) 안에서 쓴다.
@Service
@RequiredArgsConstructor
public class AccessGuard {

    private final UserRepository userRepository;

    // 토큰은 유효한데 회원이 DB에 없으면(탈퇴) 401. 있으면 그 회원을 돌려준다.
    public User requireUser(LoginUser loginUser) {
        return requireUser(loginUser.id());
    }

    // id만 있을 때(UserService)도 같은 규칙을 쓰도록 한 곳에 모은 버전
    public User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(ApiErrors::loginRequired);
    }

    // "본인 또는 관리자"만 지울 수 있는 대상의 권한 검사. 통과하면 조용히 끝나고, 아니면 예외.
    // 순서: 본인이면 통과(DB 확인 없음) -> 토큰 role이 ADMIN이면 DB에서 회원 재확인
    //       (회원 없음 401, DB role이 USER면 403) -> 토큰 role이 USER면 403
    // 작성자가 없는 옛 글·댓글은 writtenByMe가 false라 관리자만 통과한다.
    public void checkOwnerOrAdmin(boolean writtenByMe, LoginUser loginUser, String forbiddenMessage) {
        if (writtenByMe) {
            return;
        }
        if (loginUser.role() == UserRole.ADMIN) {
            User user = requireUser(loginUser);
            if (user.getRole() == UserRole.ADMIN) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage);
    }
}
