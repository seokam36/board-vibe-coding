package com.example.demo.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.user.domain.User;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.dto.UserResponse;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    private ResponseStatusException duplicated() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디 또는 닉네임입니다.");
    }
}
