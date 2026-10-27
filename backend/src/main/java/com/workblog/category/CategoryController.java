package com.workblog.category;

import com.workblog.category.CategoryDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    /** 전체 트리(발행 글 수 포함). 로그인 사용자 모두. */
    @GetMapping
    public List<CategoryNode> tree() {
        return service.tree();
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of("maxDepth", Category.MAX_DEPTH);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryNode create(@Valid @RequestBody CreateCategoryRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryNode update(@PathVariable Long id, @Valid @RequestBody UpdateCategoryRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/move")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@PathVariable Long id, @RequestParam int direction) {
        service.reorder(id, direction);
    }

    /** 끌어 놓기: parentId(없으면 최상위) 아래 index 번째(0부터) 자리에 놓는다. 부모가 바뀌면 하위 트리째 이동(5단계 규칙 검사). */
    @PostMapping("/{id}/place")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void place(@PathVariable Long id, @Valid @RequestBody PlaceCategoryRequest req) {
        service.place(id, req.parentId(), req.index());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
