package com.example.demo.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    // 가입 전에 아이디 중복을 확인한다
    boolean existsByUsername(String username);

    // 로그인 때 아이디로 사용자를 찾는다
    Optional<User> findByUsername(String username);
}
