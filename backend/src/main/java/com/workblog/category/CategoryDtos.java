package com.workblog.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CategoryDtos {
    private CategoryDtos() {}

    /** 트리 노드. postCount = 직접 연결된 발행 글, totalCount = 하위 포함. restrictedDepartments = 이 카테고리에 직접 건 열람 제한 부서(빈 목록 = 제한 없음, 하위에도 적용) */
    public record CategoryNode(Long id, String name, Long parentId, int depth, int sortOrder,
                               long postCount, long totalCount, List<CategoryNode> children, List<String> restrictedDepartments) {
        public CategoryNode(Long id, String name, Long parentId, int depth, int sortOrder, long postCount, long totalCount, List<CategoryNode> children) {
            this(id, name, parentId, depth, sortOrder, postCount, totalCount, children, List.of());
        }
    }

    /** 글에 붙는 카테고리 요약. path = 루트부터 자기까지의 이름 */
    public record CategoryRef(Long id, String name, List<String> path, List<Long> pathIds) {}

    public record CreateCategoryRequest(@NotBlank @Size(max = 50) String name, Long parentId) {}

    /** parentId 를 바꾸면 하위 전체가 함께 이동. moveToRoot=true 면 최상위로. */
    /** restrictedDepartments: 열람 제한 부서 목록. null 이면 바꾸지 않고, 빈 목록이면 제한을 푼다(관리자 카테고리 관리 화면). */
    public record UpdateCategoryRequest(@NotBlank @Size(max = 50) String name, Long parentId, Boolean moveToRoot, Integer sortOrder,
                                        @Size(max = CategoryAccess.MAX_DEPARTMENTS) List<@Size(max = 100) String> restrictedDepartments) {
        public UpdateCategoryRequest(String name, Long parentId, Boolean moveToRoot, Integer sortOrder) {
            this(name, parentId, moveToRoot, sortOrder, null);
        }
    }

    /** 끌어 놓기 위치. parentId=null 이면 최상위. index 는 형제 사이 0부터의 자리(범위를 넘으면 맨 끝). */
    public record PlaceCategoryRequest(Long parentId, @jakarta.validation.constraints.Min(0) int index) {}
}
