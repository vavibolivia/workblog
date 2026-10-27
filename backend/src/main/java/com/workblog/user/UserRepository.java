package com.workblog.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    /** @아이디 멘션 대상 찾기(대소문자 무시) */
    Optional<User> findFirstByUsernameIgnoreCase(String username);
    List<User> findAllByOrderByCreatedAtAsc();
}
