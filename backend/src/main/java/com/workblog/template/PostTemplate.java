package com.workblog.template;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/** 글 템플릿(2026-10-27). 새 글 쓰기에서 본문·기본 카테고리·기본 태그를 채운다. 관리자가 관리한다. */
@Entity
@Table(name = "post_templates")
@EntityListeners(AuditingEntityListener.class)
public class PostTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 기본 카테고리(없으면 null). 카테고리를 지우면 DB 가 null 로 바꾼다. */
    @Column(name = "category_id")
    private Long categoryId;

    /** 쉼표 구분(Post 와 같은 규칙) */
    @Column(nullable = false, length = 500)
    private String tags = "";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PostTemplate() {}

    public PostTemplate(String name, String description, String content, Long categoryId, String tags, int sortOrder) {
        apply(name, description, content, categoryId, tags, sortOrder);
    }

    public void apply(String name, String description, String content, Long categoryId, String tags, int sortOrder) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.categoryId = categoryId;
        this.tags = tags == null ? "" : tags;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getContent() { return content; }
    public Long getCategoryId() { return categoryId; }
    public String getTags() { return tags; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
