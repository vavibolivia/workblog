package com.workblog.auth;

import com.workblog.common.ApiException;
import com.workblog.config.WorkblogProperties;
import com.workblog.user.User;
import com.workblog.user.UserDtos.SignupRequest;
import com.workblog.user.UserDtos.UserResponse;
import com.workblog.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "인증", description = "로그인·회원 가입·내 계정")
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @jakarta.validation.constraints.Size(max = 100) String username,
                               @NotBlank @jakarta.validation.constraints.Size(max = 72) String password) {} // BCrypt 는 72바이트까지만 판정
    public record LoginResponse(String token, long expiresIn, UserResponse user) {}
    /** 가입 결과: 즉시 승인이면 token 이 채워지고, 승인 대기면 token 은 null. */
    public record SignupResponse(boolean approved, String message, String token, Long expiresIn, UserResponse user) {}
    /** 로그인 전에도 볼 수 있는 공개 설정. drawioUrl: 다이어그램 편집기 주소. passwordMinLength: 새 비밀번호 최소 길이(글자 종류 제한 없음). */
    public record AuthOptions(boolean signupEnabled, boolean signupRequiresApproval, String drawioUrl, int passwordMinLength) {}

    /** 같은 접속 IP 의 가입 요청 한도(한 시간). 사내 공유 IP 를 감안해 넉넉히 둔다. */
    static final int SIGNUP_PER_IP_HOUR = 20;
    private final com.workblog.common.HourlyThrottle signupThrottle = new com.workblog.common.HourlyThrottle();

    private final AuthenticationManager authManager;
    private final JwtService jwt;
    private final UserService users;
    private final WorkblogProperties props;
    private final LoginAttemptGuard guard;
    private final com.workblog.user.PasswordPolicy passwordPolicy;

    public AuthController(AuthenticationManager authManager, JwtService jwt, UserService users, WorkblogProperties props,
                          LoginAttemptGuard guard, com.workblog.user.PasswordPolicy passwordPolicy) {
        this.authManager = authManager;
        this.jwt = jwt;
        this.users = users;
        this.props = props;
        this.guard = guard;
        this.passwordPolicy = passwordPolicy;
    }

    @Operation(summary = "로그인", description = "웹 세션용 JWT 를 발급한다. 외부 프로그램은 개인 API 토큰 사용을 권장한다. "
            + "같은 아이디로 5회 연속 실패하면 10분간 잠기고 그동안은 429(남은 시간 안내)를 준다.")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, jakarta.servlet.http.HttpServletRequest http) {
        // 잠금 단위는 (아이디, 접속 IP): 다른 곳에서 일부러 틀려 계정 주인을 잠그지 못하게
        String ip = http.getRemoteAddr();
        guard.tryAcquire(req.username(), ip).ifPresent(d -> { throw locked(d); });
        Authentication auth;
        try {
            auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (BadCredentialsException e) {
            if (guard.release(req.username(), ip, false)) throw locked(LoginAttemptGuard.LOCK);
            throw e;
        } catch (RuntimeException e) {
            guard.abort(req.username(), ip); // 비활성 계정 등: 예약만 풀고 실패 횟수는 지우지 않음
            throw e;
        }
        guard.release(req.username(), ip, true);
        User user = ((AuthPrincipal) auth.getPrincipal()).getUser();
        return new LoginResponse(jwt.issue(user), jwt.expirationSeconds(), UserResponse.from(user));
    }

    @Operation(summary = "가입 설정 조회", description = "로그인 화면이 가입 링크를 보일지 정한다.")
    @GetMapping("/options")
    public AuthOptions options() {
        WorkblogProperties.Signup s = signup();
        String drawio = props.drawio() == null || props.drawio().url() == null || props.drawio().url().isBlank() ? defaultDrawio() : props.drawio().url();
        return new AuthOptions(s.enabled(), s.approval(), drawio, passwordPolicy.minLength());
    }

    @Operation(summary = "회원 가입", description = "아이디는 각자 정한다. 즉시 승인 설정이면 바로 로그인 토큰을 준다.")
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest req, jakarta.servlet.http.HttpServletRequest http) {
        // 가입은 로그인 없이 부른다 → 한 곳(IP)에서 계정·가입 메일을 쏟아 내지 못하게 한 시간 횟수를 제한
        if (signup().enabled() && !signupThrottle.allow(http.getRemoteAddr(), SIGNUP_PER_IP_HOUR)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "같은 곳에서 가입 요청이 너무 많습니다. 한 시간 뒤에 다시 시도하거나 관리자에게 계정을 요청하세요.");
        }
        WorkblogProperties.Signup s = signup();
        if (!s.enabled()) throw ApiException.forbidden("회원 가입이 닫혀 있습니다. 관리자에게 계정을 요청하세요.");
        User u = users.signup(req, !s.approval());
        if (s.approval()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SignupResponse(false,
                    "가입 신청을 받았습니다. 관리자가 승인하면 로그인할 수 있습니다.", null, null, UserResponse.from(u)));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(true, "가입했습니다.",
                jwt.issue(u), jwt.expirationSeconds(), UserResponse.from(u)));
    }

    @Operation(summary = "내 계정")
    @GetMapping("/me")
    public UserResponse me(@CurrentUser User user) {
        return UserResponse.from(user);
    }

    private static ApiException locked(java.time.Duration remaining) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인에 " + LoginAttemptGuard.MAX_FAILURES + "회 연속 실패해 이 아이디가 잠겼습니다. "
                + LoginAttemptGuard.humanize(remaining) + " 뒤에 다시 시도하세요.");
    }

    private WorkblogProperties.Signup signup() {
        return props.signup() == null ? new WorkblogProperties.Signup(true, false) : props.signup();
    }

    @org.springframework.beans.factory.annotation.Value("${EXTRA_STATIC_DIR:./static-extra}")
    private String extraStaticDir;

    /** DRAWIO_URL 을 비워 두면: 자체 호스팅 draw.io(static-extra/drawio)가 있으면 같은 서버의 /drawio, 없으면 공개 embed.diagrams.net. */
    private String defaultDrawio() {
        boolean selfHosted = extraStaticDir != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(extraStaticDir, "drawio", "index.html"));
        return selfHosted ? "/drawio" : "https://embed.diagrams.net";
    }
}
