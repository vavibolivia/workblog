package com.workblog.notification;

import com.workblog.comment.Comment;
import com.workblog.common.ApiException;
import com.workblog.common.PageResponse;
import com.workblog.notification.NotificationDtos.*;
import com.workblog.post.HtmlSanitizer;
import com.workblog.post.Post;
import com.workblog.post.PostService;
import com.workblog.user.User;
import com.workblog.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 알림 만들기·읽기. 규칙:
 * <ul>
 *   <li>댓글: 글 작성자에게 COMMENT. 답글: 답한 댓글의 작성자에게 REPLY(글 작성자에게도 COMMENT).</li>
 *   <li>댓글·글 본문의 @아이디: 그 사용자에게 MENTION. 글 본문 멘션은 글마다 한 사람에게 한 번만.</li>
 *   <li>한 번의 댓글로 같은 사람에게 알림은 하나(REPLY &gt; MENTION &gt; COMMENT 순으로 하나만).</li>
 *   <li>자기 자신 · 비활성 사용자 · 그 글을 볼 수 없는 사용자(비공개·임시·열람 제한)에게는 만들지 않는다.</li>
 *   <li>목록을 볼 때 글을 더는 볼 수 없으면 제목·내용을 비우고 available=false 로 준다.</li>
 * </ul>
 */
@Service
@Transactional
public class NotificationService {
    private static final int EXCERPT = 80;

    private final NotificationRepository repo;
    private final UserRepository users;

    public NotificationService(NotificationRepository repo, UserRepository users) {
        this.repo = repo;
        this.users = users;
    }

    // ---------- 만들기 ----------

    /** 새 댓글·답글. replyTarget = 사용자가 답글을 누른 댓글(답글의 답글이면 그 답글), 일반 댓글이면 null. */
    public void onComment(Comment c, Comment replyTarget) {
        Post post = c.getPost();
        User actor = c.getAuthor();
        Map<Long, Target> targets = new LinkedHashMap<>();
        if (replyTarget != null && !replyTarget.isDeleted()) put(targets, replyTarget.getAuthor(), Notification.Type.REPLY);
        for (String name : MentionParser.parse(c.getContent())) {
            users.findFirstByUsernameIgnoreCase(name).ifPresent(u -> put(targets, u, Notification.Type.MENTION));
        }
        put(targets, post.getAuthor(), Notification.Type.COMMENT);
        for (Target t : targets.values()) {
            if (deliverable(t.user, actor, post)) repo.save(new Notification(t.user, actor, t.type, post, c));
        }
    }

    /** 글 작성·수정 뒤: 본문의 @아이디 중 아직 알림을 받지 않은 사람에게 MENTION. 임시 저장 중이던 멘션은 발행할 때 간다. */
    public void onPostSaved(Post p, User editor) {
        String plain = p.getContentFormat() == Post.ContentFormat.HTML ? HtmlSanitizer.toPlainText(p.getContent()) : p.getContent();
        for (String name : MentionParser.parse(plain)) {
            User u = users.findFirstByUsernameIgnoreCase(name).orElse(null);
            if (u == null || !deliverable(u, editor, p)) continue;
            if (repo.existsByRecipientIdAndPostIdAndTypeAndCommentIsNull(u.getId(), p.getId(), Notification.Type.MENTION)) continue;
            repo.save(new Notification(u, editor, Notification.Type.MENTION, p, null));
        }
    }

    private record Target(User user, Notification.Type type) {}

    private static void put(Map<Long, Target> m, User u, Notification.Type type) {
        if (u != null) m.putIfAbsent(u.getId(), new Target(u, type));
    }

    static boolean deliverable(User recipient, User actor, Post post) {
        if (recipient == null || !recipient.isActive()) return false;
        if (actor != null && recipient.getId().equals(actor.getId())) return false;
        return !post.isDeleted() && PostService.canView(post, recipient);
    }

    // ---------- 읽기 ----------

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(User me, boolean unreadOnly, int page, int size) {
        var result = repo.findForRecipient(me.getId(), unreadOnly, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        return PageResponse.of(result, n -> toResponse(n, me));
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(User me) {
        return new UnreadCount(repo.countByRecipientIdAndReadAtIsNull(me.getId()));
    }

    public NotificationResponse markRead(Long id, User me) {
        Notification n = repo.findById(id).filter(x -> x.getRecipient().getId().equals(me.getId()))
                .orElseThrow(() -> ApiException.notFound("알림"));
        n.markRead();
        return toResponse(n, me);
    }

    public UnreadCount markAllRead(User me) {
        repo.markAllRead(me.getId(), Instant.now());
        return new UnreadCount(0);
    }

    private static NotificationResponse toResponse(Notification n, User me) {
        Post p = n.getPost();
        boolean available = !p.isDeleted() && PostService.canView(p, me);
        Comment c = n.getComment();
        String excerpt = available && c != null && !c.isDeleted() ? excerpt(c.getContent()) : null;
        User a = n.getActor();
        return new NotificationResponse(n.getId(), n.getType(), a == null ? null : new Actor(a.getId(), a.getUsername(), a.getDisplayName()),
                p.getId(), available ? p.getTitle() : null, c == null ? null : c.getId(), excerpt, n.isRead(), available, n.getCreatedAt());
    }

    private static String excerpt(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= EXCERPT ? t : t.substring(0, EXCERPT) + "…";
    }
}
