package com.workblog.related;

import com.workblog.auth.CurrentUser;
import com.workblog.related.RelatedService.RelatedResponse;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관련 글", description = "관련 글(태그·카테고리 점수) · 이 글을 언급한 글")
@RestController
@RequestMapping("/api/posts")
public class RelatedController {
    private final RelatedService service;

    public RelatedController(RelatedService service) {
        this.service = service;
    }

    @Operation(summary = "관련 글 · 백링크",
            description = "related: 점수(공통 태그 수×2 + 같은 카테고리 1) 높은 순 5개. backlinks: 본문에 /posts/{id} 링크가 있는 글. 볼 수 없는 글은 빠진다.")
    @GetMapping("/{id}/related")
    public RelatedResponse related(@PathVariable Long id, @CurrentUser User viewer) {
        return service.forPost(id, viewer);
    }
}
