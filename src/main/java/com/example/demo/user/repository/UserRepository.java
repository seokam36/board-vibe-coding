package com.example.demo.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    // 가입 전에 아이디 중복을 확인한다
    boolean existsByUsername(String username);

    // 가입 전에 닉네임 중복을 확인한다 (대소문자 구분: DB unique와 같은 기준)
    boolean existsByNickname(String nickname);

    // 로그인 때 아이디로 사용자를 찾는다
    Optional<User> findByUsername(String username);

    // 닉네임 컬럼만 바꾼다. findById로 읽은 User를 통째로 저장(merge)하면 모든 컬럼을 UPDATE해서,
    // 동시에 커밋된 다른 변경(예: 비밀번호)을 옛 값으로 되돌리는 lost update가 생기기 때문이다.
    // 영속성 컨텍스트: open-in-view=false이고 호출마다 자기 트랜잭션이라 남는 엔티티가 없어 clearAutomatically는 필요 없다.
    // 반환값 = 바뀐 행 수(0이면 그 사이 회원이 삭제됨)
    @Modifying
    @Transactional
    @Query("update User u set u.nickname = :nickname where u.id = :id")
    int updateNickname(@Param("id") Long id, @Param("nickname") String nickname);

    // 비밀번호(해시) 컬럼만 바꾼다. 이유는 updateNickname과 같다.
    @Modifying
    @Transactional
    @Query("update User u set u.password = :password where u.id = :id")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    // 회원 행을 직접 지운다. findById로 읽은 엔티티를 delete()하지 않고 쿼리로 지우는 이유는 updateNickname과 같다
    // (detached 엔티티 merge가 끼어들지 않게). 반환값 = 지운 행 수(0이면 이미 삭제됨)
    // MANDATORY: 바깥 트랜잭션 없이 부르면 예외(글 작성자 비우기와 반드시 한 트랜잭션이어야 하므로)
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from User u where u.id = :id")
    int deleteUserById(@Param("id") Long id);

    // 회원 행을 FOR UPDATE로 잠근다(없으면 빈 값). JPA PESSIMISTIC_WRITE는 DB에 따라 FOR NO KEY UPDATE가 될 수 있는데,
    // 그것으로는 글 INSERT의 FK 검사(FOR KEY SHARE)를 막지 못하므로 네이티브로 FOR UPDATE를 분명히 쓴다.
    @Query(value = "select id from users where id = :id for update", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> lockById(@Param("id") Long id);
}
