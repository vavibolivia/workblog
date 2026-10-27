package com.workblog.category;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/** 카테고리 트리 노드. depth 1~5, path 는 '/1/5/9/' 형태(자기 포함 조상 id). */
@Entity
@Table(name = "categories")
@EntityListeners(AuditingEntityListener.class)
public class Category {
    public static final int MAX_DEPTH = 5;
    public static final String DEFAULT_NAME = "일반";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false)
    private short depth;

    @Column(nullable = false, length = 200)
    private String path = "";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {}

    public Category(String name, Category parent, int sortOrder) {
        this.name = name;
        this.parent = parent;
        this.depth = (short) (parent == null ? 1 : parent.getDepth() + 1);
        this.sortOrder = sortOrder;
    }

    /** id 가 생긴 뒤 호출해 path 를 확정한다. */
    void refreshPath() {
        this.depth = (short) (parent == null ? 1 : parent.getDepth() + 1);
        this.path = (parent == null ? "/" : parent.getPath()) + id + "/";
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Category getParent() { return parent; }
    public Long getParentId() { return parent == null ? null : parent.getId(); }
    public int getDepth() { return depth; }
    public String getPath() { return path; }
    public int getSortOrder() { return sortOrder; }

    void setName(String name) { this.name = name; }
    void setParent(Category parent) { this.parent = parent; }
    void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
