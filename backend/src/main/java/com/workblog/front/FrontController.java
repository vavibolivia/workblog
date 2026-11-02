package com.workblog.front;

import com.workblog.auth.CurrentUser;
import com.workblog.front.FrontService.FrontItem;
import com.workblog.front.FrontService.ItemRef;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "대문", description = "홈 맨 위에 탭으로 띄우는 글 · 공수 표. 조회는 모두(권한으로 거름), 고르기는 관리자")
@RestController
@RequestMapping("/api/front")
public class FrontController {
    private final FrontService service;

    public FrontController(FrontService service) {
        this.service = service;
    }

    public record FrontRequest(@NotNull List<ItemRef> items) {}

    @Operation(summary = "대문 항목", description = "순서대로. 보는 사람이 볼 수 없는 글은 빠진다")
    @GetMapping
    public List<FrontItem> list(@CurrentUser User user) {
        return service.list(user);
    }

    @Operation(summary = "대문 고르기", description = "관리자. items = [{kind: POST|MM_SHEET, refId}] 순서대로, 나머지는 내린다(최대 30)")
    @PutMapping
    public List<FrontItem> replace(@Valid @RequestBody FrontRequest req, @CurrentUser User user) {
        return service.replace(req.items(), user);
    }
}
