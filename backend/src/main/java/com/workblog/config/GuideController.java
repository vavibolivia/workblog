package com.workblog.config;

import com.workblog.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 사람·Claude Code 용 문서를 제공한다. 원본은 저장소 docs/ 와 scripts/ 이고 빌드 때 jar 의 guide/ 로 복사된다(pom.xml resources).
 * 토큰 없이 열린다(비밀 정보 없음). 대화형 문서는 /swagger-ui/index.html.
 */
@Tag(name = "문서", description = "API 사용 안내·Claude Code 연동 가이드·CLI 스크립트")
@RestController
@RequestMapping("/api/guide")
public class GuideController {
    public record GuideInfo(String id, String title, String path) {}

    private static final List<GuideInfo> DOCS = List.of(
            new GuideInfo("claude-code", "Claude Code 연동 가이드", "guide/CLAUDE_CODE_GUIDE.md"),
            new GuideInfo("api", "API 사용 안내", "guide/API.md"));
    private static final Map<String, String> FILES = Map.of("workblog.py", "guide/workblog.py");

    @Operation(summary = "문서 목록")
    @GetMapping
    public List<GuideInfo> list() {
        return DOCS;
    }

    @Operation(summary = "문서 본문(Markdown)", description = "id: claude-code | api. 파일: workblog.py (CLI 스크립트)")
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable String id, HttpServletRequest req) throws IOException {
        String path = DOCS.stream().filter(d -> d.id().equals(id)).map(GuideInfo::path).findFirst().orElse(FILES.get(id));
        if (path == null) throw ApiException.notFound("문서");
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) throw ApiException.notFound("문서");
        // 문서의 {{BASE_URL}} 을 이 서버의 주소로 바꾼다(저장소 원본에는 사내 주소를 적지 않는다)
        byte[] body = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8).replace("{{BASE_URL}}", baseUrl(req)).getBytes(StandardCharsets.UTF_8);
        MediaType type = id.endsWith(".py") ? MediaType.parseMediaType("text/x-python;charset=UTF-8") : MediaType.parseMediaType("text/markdown;charset=UTF-8");
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private static String baseUrl(HttpServletRequest req) {
        String proto = first(req.getHeader("X-Forwarded-Proto"), req.getScheme());
        String host = first(req.getHeader("X-Forwarded-Host"), req.getHeader("Host"));
        return host == null ? "" : proto + "://" + host;
    }

    private static String first(String a, String b) { return a == null || a.isBlank() ? b : a.split(",")[0].trim(); }
}
