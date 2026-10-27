package com.workblog.audit;

import com.workblog.audit.AuditDtos.*;
import com.workblog.auth.CurrentUser;
import com.workblog.common.PageResponse;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "감사 로그", description = "글 열람 기록(10분 안 반복 열람은 1건)")
@RestController
@RequestMapping("/api")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @Operation(summary = "글 열람자(작성자·관리자)", description = "최근 열람 순 50명과 각자의 기록 횟수·마지막 열람 시각. 작성자 본인은 빠진다.")
    @GetMapping("/posts/{id}/viewers")
    public ViewersResponse viewers(@PathVariable Long id, @CurrentUser User viewer) {
        return service.viewers(id, viewer);
    }

    @Operation(summary = "열람 감사 로그(관리자)", description = "postId·userId·from·to(yyyy-MM-dd, 서울 날짜, 둘 다 포함)로 거른다. 최신순. API 토큰으로는 호출할 수 없다.")
    @GetMapping("/admin/audit/views")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<ViewLogEntry> views(@RequestParam(required = false) Long postId,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @CurrentUser User actor) {
        return service.search(postId, userId, from, to, page, size, actor);
    }
}
