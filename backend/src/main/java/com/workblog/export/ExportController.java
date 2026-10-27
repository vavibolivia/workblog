package com.workblog.export;

import com.workblog.auth.CurrentUser;
import com.workblog.common.ApiException;
import com.workblog.export.ExportService.Exported;
import com.workblog.export.ExportService.Format;
import com.workblog.post.PostDtos.PostDetail;
import com.workblog.post.PostService;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 글 내보내기: GET /api/posts/{id}/export?format=html|md|docx[&attachments=true]. PDF 는 화면의 '인쇄'. */
@Tag(name = "내보내기", description = "글을 HTML·Markdown·Word 파일로")
@RestController
@RequestMapping("/api")
public class ExportController {
    private final PostService posts;
    private final ExportService exporter;

    public ExportController(PostService posts, ExportService exporter) {
        this.posts = posts;
        this.exporter = exporter;
    }

    @Operation(summary = "글 내보내기", description = "format: html(단독 HTML 파일), md(Markdown), docx(Word). attachments=true 면 첨부 목록을 문서 끝에 붙인다. 응답은 파일(Content-Disposition: attachment).")
    @GetMapping("/posts/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @Parameter(description = "html | md | docx") @RequestParam(defaultValue = "html") String format,
                                         @RequestParam(defaultValue = "true") boolean attachments,
                                         @CurrentUser User viewer, HttpServletRequest req) throws IOException {
        Format f = switch (format.toLowerCase(Locale.ROOT)) {
            case "html", "htm" -> Format.HTML;
            case "md", "markdown" -> Format.MARKDOWN;
            case "docx", "word" -> Format.DOCX;
            default -> throw ApiException.badRequest("format 은 html, md, docx 중 하나입니다.");
        };
        PostDetail post = posts.getDetail(id, viewer, false);
        Exported out = exporter.export(post, f, attachments, baseUrl(req));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(out.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(out.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(out.bytes());
    }

    /** 내보낸 파일의 사진·링크를 절대 주소로 만들기 위한 서버 주소(프록시 헤더 우선). */
    private static String baseUrl(HttpServletRequest req) {
        String proto = first(req.getHeader("X-Forwarded-Proto"), req.getScheme());
        String host = first(req.getHeader("X-Forwarded-Host"), req.getHeader("Host"));
        if (host == null) return "";
        return proto + "://" + host;
    }

    private static String first(String a, String b) { return a == null || a.isBlank() ? b : a.split(",")[0].trim(); }
}
