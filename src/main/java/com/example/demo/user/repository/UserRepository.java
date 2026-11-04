package com.example.demo.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    // 가입 전에 아이디 중복을 확인한다
    boolean existsByUsername(String username);
}
