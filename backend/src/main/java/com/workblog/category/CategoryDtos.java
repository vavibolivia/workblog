package com.workblog.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CategoryDtos {
    private CategoryDtos() {}

    /** 트리 노드. postCount = 직접 연결된 발행 글, totalCount = 하위 포함 */
    public record CategoryNode(Long id, String name, Long parentId, int depth, int sortOrder,
                               long postCount, long totalCount, List<CategoryNode> children) {}

    /** 글에 붙는 카테고리 요약. path = 루트부터 자기까지의 이름 */
    public record CategoryRef(Long id, String name, List<String> path, List<Long> pathIds) {}

    public record CreateCategoryRequest(@NotBlank @Size(max = 50) String name, Long parentId) {}

    /** parentId 를 바꾸면 하위 전체가 함께 이동. moveToRoot=true 면 최상위로. */
    public record UpdateCategoryRequest(@NotBlank @Size(max = 50) String name, Long parentId, Boolean moveToRoot, Integer sortOrder) {}
}
