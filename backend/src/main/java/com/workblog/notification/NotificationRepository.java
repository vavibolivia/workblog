package com.workblog.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query(value = "SELECT n FROM Notification n LEFT JOIN FETCH n.actor JOIN FETCH n.post p JOIN FETCH p.author LEFT JOIN FETCH n.comment " +
                   " WHERE n.recipient.id = :rid AND (:unreadOnly = FALSE OR n.readAt IS NULL) ORDER BY n.createdAt DESC, n.id DESC",
           countQuery = "SELECT count(n) FROM Notification n WHERE n.recipient.id = :rid AND (:unreadOnly = FALSE OR n.readAt IS NULL)")
    Page<Notification> findForRecipient(@Param("rid") Long recipientId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /** 글 본문 멘션은 같은 사람에게 한 번만(수정할 때마다 다시 보내지 않는다). */
    boolean existsByRecipientIdAndPostIdAndTypeAndCommentIsNull(Long recipientId, Long postId, Notification.Type type);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.recipient.id = :rid AND n.readAt IS NULL")
    int markAllRead(@Param("rid") Long recipientId, @Param("now") Instant now);
}
