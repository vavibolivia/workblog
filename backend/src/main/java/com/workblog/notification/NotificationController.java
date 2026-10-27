package com.workblog.notification;

import com.workblog.auth.CurrentUser;
import com.workblog.common.PageResponse;
import com.workblog.notification.NotificationDtos.*;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@Tag(name = "알림", description = "내 글에 댓글 · 내 댓글에 답글 · @아이디 멘션")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "내 알림 목록", description = "최신순. unreadOnly=true 면 읽지 않은 것만. 볼 수 없게 된 글의 알림은 available=false 이고 제목·내용이 비어 있다.")
    @GetMapping
    public PageResponse<NotificationResponse> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @CurrentUser User me) {
        return service.list(me, unreadOnly, page, size);
    }

    @Operation(summary = "읽지 않은 알림 수")
    @GetMapping("/unread-count")
    public UnreadCount unreadCount(@CurrentUser User me) {
        return service.unreadCount(me);
    }

    @Operation(summary = "알림 하나 읽음 처리")
    @PostMapping("/{id}/read")
    public NotificationResponse read(@PathVariable Long id, @CurrentUser User me) {
        return service.markRead(id, me);
    }

    @Operation(summary = "모두 읽음 처리")
    @PostMapping("/read-all")
    public UnreadCount readAll(@CurrentUser User me) {
        return service.markAllRead(me);
    }
}
