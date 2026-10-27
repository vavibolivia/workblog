package com.workblog.stats;

import com.workblog.stats.StatsDtos.StatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 글 통계(X6). 로그인한 모든 사용자가 본다(SecurityConfig 의 `/api/**` authenticated 규칙). */
@Tag(name = "통계", description = "발행·전체 공개 글 기준 집계")
@RestController
@RequestMapping("/api/stats")
public class StatsController {
    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @Operation(summary = "글 통계", description = "days(기본 30, 1~3650) 동안의 일별 새 글 수와 전체 합계, 많이 읽은/공감 많은 글 TOP 10, 1단계 카테고리별·부서별 글 수, 작성자 TOP 10. 삭제·임시 저장·비공개 글과 내가 볼 수 없는 열람 제한 카테고리 글은 제외.")
    @GetMapping
    public StatsResponse stats(@RequestParam(defaultValue = "30") int days, @com.workblog.auth.CurrentUser com.workblog.user.User viewer) {
        return service.stats(days, viewer);
    }
}
