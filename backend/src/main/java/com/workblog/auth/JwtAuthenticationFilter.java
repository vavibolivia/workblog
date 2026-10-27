package com.workblog.auth;

import com.workblog.apitoken.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 인증 헤더 처리. 두 가지를 받는다.
 * <ul>
 *   <li>웹 로그인 JWT: {@code Authorization: Bearer eyJ...}</li>
 *   <li>개인 API 토큰(외부 Claude Code·스크립트): {@code Authorization: Bearer wbt_...} 또는 {@code X-API-Key: wbt_...}</li>
 * </ul>
 * API 토큰으로는 토큰 관리와 비밀번호 변경을 할 수 없다(유출된 토큰이 스스로 늘어나거나 계정을 빼앗지 못하게).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String AUTH_METHOD_ATTR = "workblog.authMethod";

    private final JwtService jwt;
    private final AuthUserDetailsService userDetails;
    private final ApiTokenService apiTokens;

    public JwtAuthenticationFilter(JwtService jwt, AuthUserDetailsService userDetails, ApiTokenService apiTokens) {
        this.jwt = jwt;
        this.userDetails = userDetails;
        this.apiTokens = apiTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = req.getHeader("Authorization");
            String bearer = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
            String apiKey = req.getHeader("X-API-Key");
            if (apiKey == null && bearer != null && bearer.startsWith(ApiTokenService.PREFIX)) apiKey = bearer;

            Optional<String> username;
            String method;
            if (apiKey != null) {
                username = apiTokens.authenticate(apiKey.trim());
                method = "API_TOKEN";
            } else if (bearer != null) {
                username = jwt.parseUsername(bearer);
                method = "JWT";
            } else {
                username = Optional.empty();
                method = null;
            }

            if (username.isPresent()) {
                if ("API_TOKEN".equals(method) && blockedForApiToken(req)) {
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"API 토큰으로는 토큰 관리와 비밀번호 변경을 할 수 없습니다. 웹에서 로그인해 진행하세요.\"}");
                    return;
                }
                try {
                    UserDetails ud = userDetails.loadUserByUsername(username.get());
                    if (ud.isEnabled()) {
                        var auth = new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        req.setAttribute(AUTH_METHOD_ATTR, method);
                    }
                } catch (UsernameNotFoundException ignored) {
                    // 토큰이 가리키는 사용자가 없다 → 인증 없이 진행(401)
                }
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean blockedForApiToken(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri.startsWith("/api/tokens") || uri.equals("/api/users/me/password");
    }
}
