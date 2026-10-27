package com.workblog.audit;

import com.workblog.auth.AuthPrincipal;
import com.workblog.auth.JwtAuthenticationFilter;
import com.workblog.post.PostController;
import com.workblog.post.PostDtos.PostDetail;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 글 상세 조회(GET /api/posts/{id}) 응답이 나갈 때 열람 기록을 남긴다.
 * PostController 를 고치지 않고 붙이기 위해 ResponseBodyAdvice 로 둔다(조회가 성공해 PostDetail 이 나갈 때만 = 볼 권한이 있을 때만).
 * via: 개인 API 토큰이면 API_TOKEN, 그 밖(웹 로그인 JWT)은 WEB.
 */
@RestControllerAdvice(assignableTypes = PostController.class)
public class PostViewRecorder implements ResponseBodyAdvice<Object> {
    private final AuditService audit;

    public PostViewRecorder(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getMethod() != null && "get".equals(returnType.getMethod().getName())
                && PostDetail.class.equals(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof PostDetail d && request instanceof ServletServerHttpRequest sr && "GET".equals(sr.getServletRequest().getMethod())) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthPrincipal p) {
                Object method = sr.getServletRequest().getAttribute(JwtAuthenticationFilter.AUTH_METHOD_ATTR);
                PostView.Via via = "API_TOKEN".equals(method) ? PostView.Via.API_TOKEN : PostView.Via.WEB;
                try { audit.recordView(d.id(), p.getUser(), via); } catch (RuntimeException ignored) { /* 기록 실패가 조회를 막지 않는다 */ }
            }
        }
        return body;
    }
}
