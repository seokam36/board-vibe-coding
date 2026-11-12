package com.example.demo.user.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.user.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    // id로 읽을 때 인증 학교도 한 쿼리로 같이 가져온다. 응답 변환이 트랜잭션 밖에서 일어나도(예: 토큰 재발급)
    // 지연 로딩 예외가 나지 않게 하고, 쿼리를 1번으로 유지한다.
    @Override
    @EntityGraph(attributePaths = "verifiedSchool")
    Optional<User> findById(Long id);

    // 가입 전에 아이디 중복을 확인한다
    boolean existsByUsername(String username);

    // 가입 전에 닉네임 중복을 확인한다 (대소문자 구분: DB unique와 같은 기준)
    boolean existsByNickname(String nickname);

    // 이 메일 해시로 이미 인증한 회원이 있는지 (학교 메일 한 개로 계정 하나만 인증하게 하는 사전 검사. 최종 보장은 DB UNIQUE)
    boolean existsBySchoolEmailHash(String schoolEmailHash);

    // 로그인 때 아이디로 사용자를 찾는다 (인증 학교도 함께: 로그인 응답에 담기 때문)
    @EntityGraph(attributePaths = "verifiedSchool")
    Optional<User> findByUsername(String username);

    // 닉네임 컬럼만 바꾼다. findById로 읽은 User를 통째로 저장(merge)하면 모든 컬럼을 UPDATE해서,
    // 동시에 커밋된 다른 변경(예: 비밀번호)을 옛 값으로 되돌리는 lost update가 생기기 때문이다.
    // 영속성 컨텍스트: open-in-view=false이고 호출마다 자기 트랜잭션이라 남는 엔티티가 없어 clearAutomatically는 필요 없다.
    // 반환값 = 바뀐 행 수(0이면 그 사이 회원이 삭제됨)
    @Modifying
    @Transactional
    @Query("update User u set u.nickname = :nickname where u.id = :id")
    int updateNickname(@Param("id") Long id, @Param("nickname") String nickname);

    // 학교 인증 칸 3개만 채운다. 아직 인증 안 한 회원(verified_school_id IS NULL)일 때만 바뀌므로 동시에 두 번 확인해도 한 번만 기록된다.
    // 반환값 = 바뀐 행 수(0이면 그 사이 인증됐거나 회원이 삭제됨)
    @Modifying
    @Transactional
    @Query("update User u set u.verifiedSchool.id = :schoolId, u.verifiedAt = :now, u.schoolEmailHash = :emailHash "
            + "where u.id = :id and u.verifiedSchool is null")
    int markVerified(@Param("id") Long id, @Param("schoolId") Long schoolId,
            @Param("emailHash") String emailHash, @Param("now") Instant now);

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
    // 같은 잠금이 댓글·공감·비자 후기 INSERT의 FK 검사도 막는다. 공감은 탈퇴 때 ON DELETE CASCADE로 같이 지워진다.
    @Query(value = "select id from users where id = :id for update", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> lockById(@Param("id") Long id);

    // 쪽지 보내기(S14-a)용: 보낸 사람·받는 사람 회원 행을 "읽기 잠금(FOR KEY SHARE)"으로 먼저 잡고, 실제로 있는 id만 돌려준다.
    // 왜 필요한가: 탈퇴(lockById의 FOR UPDATE)와 쪽지 INSERT(FK 검사가 글·방 행에 KEY SHARE를 잡은 채 회원 행을 기다림)가
    // 서로 반대 순서로 잠그면 교착(40P01 -> 500)이 난다. 쪽지도 "회원 행부터" 잡으면 고리가 안 생긴다:
    //   - 탈퇴가 먼저 회원 행을 잡았으면: 쪽지는 여기(아직 아무 잠금도 없는 상태)에서 기다리고, 탈퇴가 끝난 뒤 행이 없어서 401/404.
    //   - 쪽지가 먼저 잡았으면: 탈퇴가 lockById(탈퇴의 첫 잠금)에서 기다리고, 아직 아무것도 잡지 않았으니 고리가 없다.
    // KEY SHARE끼리는 충돌하지 않아 쪽지끼리는 안 막는다. 닉네임 변경은 nickname이 UNIQUE(키 칸)라 KEY SHARE와 충돌해
    // 쪽지 쪽이 끝날 때까지 잠깐 기다릴 뿐이다(그 사이 서로 쥔 잠금이 없어 고리는 안 생긴다). 비밀번호 변경처럼 키 칸이 아닌 UPDATE는 충돌하지 않는다.
    // order by id: 여러 행을 늘 같은 순서로 잡는다.
    @Query(value = "select id from users where id in (:ids) order by id for key share", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> lockForKeyShare(@Param("ids") Collection<Long> ids);
}
