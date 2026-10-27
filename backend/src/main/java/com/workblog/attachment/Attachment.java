package com.workblog.attachment;

import com.workblog.user.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/** 업로드된 파일 한 건. post 는 글을 저장할 때 연결된다(작성 중 업로드는 post 없음). */
@Entity
@Table(name = "attachments")
@EntityListeners(AuditingEntityListener.class)
public class Attachment {
    public enum Kind { IMAGE, EXCEL, FILE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 36)
    private String publicId;

    @Column(name = "post_id")
    private Long postId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id")
    private User uploader;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "stored_name", nullable = false, length = 255)
    private String storedName;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Attachment() {}

    public Attachment(String publicId, User uploader, String originalName, String storedName, String contentType, long sizeBytes, Kind kind) {
        this.publicId = publicId;
        this.uploader = uploader;
        this.originalName = originalName;
        this.storedName = storedName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public Long getPostId() { return postId; }
    public User getUploader() { return uploader; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public Kind getKind() { return kind; }
    public Instant getCreatedAt() { return createdAt; }

    public void attachTo(Long postId) { this.postId = postId; }
    public String url() { return "/api/files/" + publicId; }

    /** 글에서 첨부 해제(파일은 남는다). */
    public void detach() { this.postId = null; }
}
