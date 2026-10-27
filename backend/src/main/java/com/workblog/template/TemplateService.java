package com.workblog.template;

import com.workblog.category.CategoryService;
import com.workblog.common.ApiException;
import com.workblog.post.HtmlSanitizer;
import com.workblog.post.Post;
import com.workblog.template.TemplateDtos.TemplateRequest;
import com.workblog.template.TemplateDtos.TemplateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 글 템플릿: 모두 조회, 관리자만 생성·수정·삭제(컨트롤러에서 검사). 본문은 글과 같은 HTML 정화를 거친다. */
@Service
@Transactional
public class TemplateService {
    private final TemplateRepository repo;
    private final CategoryService categories;

    public TemplateService(TemplateRepository repo, CategoryService categories) {
        this.repo = repo;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> list() {
        return repo.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(Long id) {
        return toResponse(load(id));
    }

    public TemplateResponse create(TemplateRequest req) {
        PostTemplate t = new PostTemplate(req.name().trim(), blankToNull(req.description()), body(req.content()), category(req.categoryId()),
                Post.joinTags(req.tags()), req.sortOrder() == null ? 0 : req.sortOrder());
        return toResponse(repo.save(t));
    }

    public TemplateResponse update(Long id, TemplateRequest req) {
        PostTemplate t = load(id);
        t.apply(req.name().trim(), blankToNull(req.description()), body(req.content()), category(req.categoryId()),
                Post.joinTags(req.tags()), req.sortOrder() == null ? t.getSortOrder() : req.sortOrder());
        return toResponse(t);
    }

    public void delete(Long id) {
        repo.delete(load(id));
    }

    private PostTemplate load(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("템플릿"));
    }

    private Long category(Long categoryId) {
        if (categoryId == null) return null;
        if (!categories.existsId(categoryId)) throw ApiException.badRequest("카테고리를 찾을 수 없습니다: " + categoryId);
        return categoryId;
    }

    private static String body(String content) {
        String html = HtmlSanitizer.clean(content);
        if (html.isBlank()) throw ApiException.badRequest("content: 본문이 비었습니다.");
        return html;
    }

    private TemplateResponse toResponse(PostTemplate t) {
        var cat = t.getCategoryId() != null && categories.existsId(t.getCategoryId()) ? categories.ref(categories.get(t.getCategoryId())) : null;
        return new TemplateResponse(t.getId(), t.getName(), t.getDescription(), t.getContent(), cat, Post.splitTags(t.getTags()),
                t.getSortOrder(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
