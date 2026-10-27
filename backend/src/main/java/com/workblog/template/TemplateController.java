package com.workblog.template;

import com.workblog.template.TemplateDtos.TemplateRequest;
import com.workblog.template.TemplateDtos.TemplateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "템플릿", description = "글 템플릿(새 글 쓰기의 '템플릿으로 시작'). 조회는 모두, 관리는 관리자")
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    @Operation(summary = "템플릿 목록", description = "sortOrder → id 순. 본문(content) 포함.")
    @GetMapping
    public List<TemplateResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody TemplateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TemplateResponse update(@PathVariable Long id, @Valid @RequestBody TemplateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
