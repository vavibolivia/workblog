package com.workblog.review;

import com.workblog.auth.CurrentUser;
import com.workblog.common.PageResponse;
import com.workblog.review.ReviewService.ReviewStatus;
import com.workblog.review.ReviewService.StaleRow;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "재검토", description = "오래 수정되지 않은 글 안내 · 내용 확인 완료 · 관리자 목록")
@RestController
@RequestMapping("/api")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @Operation(summary = "재검토 상태", description = "stale: 발행 글이고 기준일(max(마지막 수정, 내용 확인))이 staleDays 일 이상 지남. canConfirm: 작성자·관리자.")
    @GetMapping("/posts/{id}/review")
    public ReviewStatus status(@PathVariable Long id, @CurrentUser User viewer) {
        return service.status(id, viewer);
    }

    @Operation(summary = "내용 확인 완료", description = "버전을 올리지 않고 reviewed_at 만 지금으로. 작성자·관리자, 발행 글만.")
    @PostMapping("/posts/{id}/review-ok")
    public ReviewStatus confirm(@PathVariable Long id, @CurrentUser User actor) {
        return service.confirm(id, actor);
    }

    @Operation(summary = "재검토 대상 목록(관리자)", description = "기준일이 오래된 순.")
    @GetMapping("/admin/review/stale")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<StaleRow> stale(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.stale(page, size);
    }
}
