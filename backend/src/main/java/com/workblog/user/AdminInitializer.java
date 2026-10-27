package com.workblog.user;

import com.workblog.config.WorkblogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Creates the first administrator when the user table is empty. */
@Configuration
public class AdminInitializer {
    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    @Bean
    ApplicationRunner seedAdmin(UserRepository users, PasswordEncoder encoder, WorkblogProperties props) {
        return args -> {
            if (users.count() > 0) return;
            WorkblogProperties.Admin a = props.admin();
            users.save(new User(a.username(), encoder.encode(a.password()), a.displayName(), null, "관리", User.Role.ADMIN));
            log.warn("초기 관리자 계정 생성: {} (비밀번호는 ADMIN_PASSWORD 환경변수, 기본값이면 반드시 변경하세요)", a.username());
        };
    }
}
