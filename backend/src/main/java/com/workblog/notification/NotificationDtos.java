package com.workblog.notification;

import java.time.Instant;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record Actor(Long id, String username, String displayName) {}

    /**
     * available=false 면 지금은 볼 수 없는 글(비공개·임시 저장·열람 제한·삭제로 바뀜)이라 postTitle·excerpt 를 비운다.
     * commentId 는 댓글·답글·댓글 멘션일 때만, 글 본문 멘션이면 null.
     */
    public record NotificationResponse(Long id, Notification.Type type, Actor actor, Long postId, String postTitle,
                                       Long commentId, String excerpt, boolean read, boolean available, Instant createdAt) {}

    public record UnreadCount(long count) {}
}
