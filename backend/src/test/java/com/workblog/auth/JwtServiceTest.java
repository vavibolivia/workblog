package com.workblog.auth;

import com.workblog.config.WorkblogProperties;
import com.workblog.user.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef"; // 48 bytes >= 32

    private static WorkblogProperties props(String secret, long minutes) {
        return new WorkblogProperties(
                new WorkblogProperties.Jwt(secret, minutes),
                new WorkblogProperties.Admin("admin", "pw", "관리자"),
                new WorkblogProperties.Cors(List.of("http://localhost:5173")),
                new WorkblogProperties.Storage("/tmp/wb", 20),
                new WorkblogProperties.Signup(true, false),
                new WorkblogProperties.Drawio("https://embed.diagrams.net"));
    }

    private static User user(long id, String username, User.Role role) throws Exception {
        User u = new User(username, "hashed", "표시이름", null, null, role);
        Field f = User.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(u, id);
        return u;
    }

    @Test
    void issue_then_parseUsername_round_trips() throws Exception {
        JwtService svc = new JwtService(props(SECRET, 60));
        String token = svc.issue(user(7L, "hong", User.Role.USER));
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(svc.parseUsername(token)).contains("hong");
    }

    @Test
    void expirationSeconds_is_minutes_times_60() {
        assertThat(new JwtService(props(SECRET, 90)).expirationSeconds()).isEqualTo(5400L);
    }

    @Test
    void tampered_signature_is_rejected() throws Exception {
        JwtService svc = new JwtService(props(SECRET, 60));
        String token = svc.issue(user(1L, "hong", User.Role.ADMIN));
        String[] parts = token.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        parts[2] = parts[2].substring(0, parts[2].length() - 1) + (last == 'A' ? 'B' : 'A');
        String tampered = String.join(".", parts);
        assertThat(svc.parseUsername(tampered)).isEmpty();
    }

    @Test
    void tampered_payload_is_rejected() throws Exception {
        JwtService svc = new JwtService(props(SECRET, 60));
        String good = svc.issue(user(1L, "hong", User.Role.USER));
        String other = svc.issue(user(2L, "admin", User.Role.ADMIN));
        // 다른 토큰의 페이로드를 끼워 넣어도 서명이 맞지 않아 거부
        String[] g = good.split("\\."), o = other.split("\\.");
        String spliced = g[0] + "." + o[1] + "." + g[2];
        assertThat(svc.parseUsername(spliced)).isEmpty();
    }

    @Test
    void token_signed_with_other_secret_is_rejected() throws Exception {
        JwtService a = new JwtService(props(SECRET, 60));
        JwtService b = new JwtService(props("ffffffffffffffffffffffffffffffffffffffffffffffff", 60));
        String token = a.issue(user(1L, "hong", User.Role.USER));
        assertThat(b.parseUsername(token)).isEmpty();
        assertThat(a.parseUsername(token)).contains("hong");
    }

    @Test
    void expired_token_is_rejected() throws Exception {
        // 만료 시간을 음수로 두면 발급 즉시 만료된 토큰이 된다
        JwtService svc = new JwtService(props(SECRET, -5));
        String token = svc.issue(user(1L, "hong", User.Role.USER));
        assertThat(svc.parseUsername(token)).isEmpty();
    }

    @Test
    void garbage_null_and_empty_tokens_give_empty() {
        JwtService svc = new JwtService(props(SECRET, 60));
        assertThat(svc.parseUsername("not.a.jwt")).isEmpty();
        assertThat(svc.parseUsername("")).isEmpty();
        assertThat(svc.parseUsername(null)).isEmpty();
        assertThat(svc.parseUsername("eyJhbGciOiJub25lIn0.eyJzdWIiOiJob25nIn0.")).isEmpty(); // alg=none
    }
}
