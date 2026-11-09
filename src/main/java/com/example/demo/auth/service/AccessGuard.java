package com.example.demo.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.common.ApiErrors;
import com.example.demo.school.domain.School;
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

    // 학교 인증을 마친 회원만 통과(관리자도 예외 없음). 인증 여부는 토큰이 아니라 매번 DB로 본다.
    // 회원 없음 401 -> 미인증 403. 통과하면 그 회원을 돌려준다(verifiedSchool은 findById의 EntityGraph로 이미 읽혀 있다).
    // 401은 프론트가 토큰 재발급을 돌리므로 미인증에는 쓰지 않고 403을 쓴다.
    public User requireVerified(LoginUser loginUser) {
        User user = requireUser(loginUser);
        if (user.getVerifiedSchool() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "학교 인증이 필요합니다.");
        }
        return user;
    }

    // 인증한 학교의 글에만 만들 수 있다. 글의 학교가 없는(옛) 글이거나 다른 학교면 403.
    // 글쓰기·댓글·공감·신고 4곳이 같이 쓴다. postSchool은 호출한 쪽이 이미 읽은 값(추가 쿼리 없음).
    public void requireSameSchool(User verifiedUser, School postSchool) {
        if (postSchool == null || !postSchool.getId().equals(verifiedUser.getVerifiedSchool().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "인증한 학교의 글에만 할 수 있습니다.");
        }
    }

    // "본인 또는 관리자"만 지울 수 있는 대상의 권한 검사. 통과하면 조용히 끝나고, 아니면 예외.
    // 순서: 본인이면 통과(DB 확인 없음) -> 토큰 role이 ADMIN이면 DB에서 회원 재확인
    //       (회원 없음 401, DB role이 USER면 403) -> 토큰 role이 USER면 403
    // 작성자가 없는 옛 글·댓글은 writtenByMe가 false라 관리자만 통과한다.
    public void checkOwnerOrAdmin(boolean writtenByMe, LoginUser loginUser, String forbiddenMessage) {
        if (writtenByMe || isAdmin(loginUser)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage);
    }

    // 관리자 전용 기능의 권한 검사. 통과하면 조용히 끝나고, 아니면 예외.
    // 토큰 role이 ADMIN이면 DB에서 회원 재확인(회원 없음 401, DB role이 USER면 403), 토큰 role이 USER면 403
    public void requireAdmin(LoginUser loginUser) {
        if (!isAdmin(loginUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 할 수 있습니다.");
        }
    }

    // 토큰 role이 ADMIN이고 DB에서도 ADMIN이면 true. 토큰이 ADMIN인데 회원이 없으면 401 예외.
    private boolean isAdmin(LoginUser loginUser) {
        return loginUser.role() == UserRole.ADMIN && requireUser(loginUser).getRole() == UserRole.ADMIN;
    }
}
