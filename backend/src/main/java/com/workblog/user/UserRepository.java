package com.workblog.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    List<User> findAllByOrderByCreatedAtAsc();
}
