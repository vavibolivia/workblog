package com.workblog.template;

import com.workblog.category.CategoryDtos.CategoryRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TemplateDtos {
    private TemplateDtos() {}

    /** category: 기본 카테고리(없거나 지워졌으면 null). */
    public record TemplateResponse(Long id, String name, String description, String content, CategoryRef category, List<String> tags,
                                   int sortOrder, Instant createdAt, Instant updatedAt) {}

    /** 생성·수정. categoryId 가 없으면 기본 카테고리 없음. sortOrder 기본 0(작을수록 위). */
    public record TemplateRequest(@NotBlank @Size(max = 100) String name,
                                  @Size(max = 300) String description,
                                  @NotBlank String content,
                                  Long categoryId,
                                  @Size(max = 10) List<@Size(max = 50) String> tags,
                                  Integer sortOrder) {}
}
