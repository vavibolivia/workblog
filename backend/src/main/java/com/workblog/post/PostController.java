package com.workblog.post;

import com.workblog.auth.CurrentUser;
import com.workblog.common.PageResponse;
import com.workblog.post.PostDtos.*;
import com.workblog.revision.RevisionDtos.RevisionDetail;
import com.workblog.revision.RevisionDtos.RevisionSummary;
import com.workblog.user.User;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "글", description = "글 목록·검색·작성·수정·삭제·이력")
@RestController
@RequestMapping("/api")
public class PostController {
    private final PostService service;
    private final com.workblog.attachment.AttachmentTextService attachmentTexts;

    public PostController(PostService service, com.workblog.attachment.AttachmentTextService attachmentTexts) {
        this.service = service;
        this.attachmentTexts = attachmentTexts;
    }

    /** 검색어가 첨부 파일 내용에 걸린 글에 matchedAttachment("파일명 — …일치 부분…")를 붙인다. */
    private PageResponse<PostSummary> withAttachmentMatches(PageResponse<PostSummary> page, String q) {
        if (q == null || q.isBlank() || page.content().isEmpty()) return page;
        var found = attachmentTexts.matches(page.content().stream().map(PostSummary::id).toList(), q);
        if (found.isEmpty()) return page;
        return new PageResponse<>(page.content().stream().map(p -> found.containsKey(p.id()) ? p.withMatchedAttachment(found.get(p.id())) : p).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    /** 목록 + 검색. q / categoryId(하위 포함) / tag / authorId / status 필터, sort(LATEST·POPULAR·VIEWS·OLDEST), 페이지네이션. */
    @Operation(summary = "글 목록·검색", description = "sort: LATEST 최신순(기본) · POPULAR 인기순(공감→조회) · VIEWS 조회순 · COMMENTS 댓글 많은 순 · OLDEST 오래된순. 공지 글은 검색어가 없을 때 항상 맨 위.")
    @GetMapping("/posts")
    public PageResponse<PostSummary> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long categoryId,
                                          @RequestParam(required = false) String tag,
                                          @RequestParam(required = false) Long authorId,
                                          @RequestParam(required = false) Post.Status status,
                                          @RequestParam(required = false) Post.Sort sort,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @CurrentUser User viewer) {
        return withAttachmentMatches(service.search(q, categoryId, tag, authorId, status, sort, page, size, viewer), q);
    }

    @GetMapping("/search")
    public PageResponse<PostSummary> search(@RequestParam String q,
                                            @RequestParam(required = false) Post.Sort sort,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @CurrentUser User viewer) {
        return withAttachmentMatches(service.search(q, null, null, null, null, sort, page, size, viewer), q);
    }

    @GetMapping("/posts/{id}")
    public PostDetail get(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean view, @CurrentUser User viewer) {
        return service.getDetail(id, viewer, view);
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostDetail create(@Valid @RequestBody PostRequest req, @CurrentUser User author) {
        return service.create(req, author);
    }

    @PutMapping("/posts/{id}")
    public PostDetail update(@PathVariable Long id, @Valid @RequestBody PostRequest req, @CurrentUser User editor) {
        return service.update(id, req, editor);
    }

    @Operation(summary = "글 부분 수정", description = "보낸 항목만 바뀐다(제목만, 본문만 등). content 를 보내고 contentFormat 을 빼면 HTML 로 본다.")
    @PatchMapping("/posts/{id}")
    public PostDetail patch(@PathVariable Long id, @Valid @RequestBody PostPatchRequest req, @CurrentUser User editor) {
        return service.patch(id, req, editor);
    }

    @Operation(summary = "글 삭제", description = "기본은 휴지통으로(복구 가능). purge=true 면 관리자가 휴지통의 글을 영구 삭제한다(이력·댓글·첨부까지, 복구 불가).")
    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean purge, @CurrentUser User editor) {
        if (purge) service.purge(id, editor); else service.delete(id, editor);
    }

    @PostMapping("/posts/{id}/undelete")
    public PostDetail undelete(@PathVariable Long id, @CurrentUser User editor) {
        return service.undelete(id, editor);
    }

    @GetMapping("/posts/deleted")
    public List<PostSummary> deleted(@CurrentUser User viewer) {
        return service.deletedPosts(viewer);
    }

    // ---- history / revisions ----

    @GetMapping("/posts/{id}/revisions")
    public List<RevisionSummary> revisions(@PathVariable Long id, @CurrentUser User viewer) {
        return service.revisions(id, viewer);
    }

    @GetMapping("/posts/{id}/revisions/{version}")
    public RevisionDetail revision(@PathVariable Long id, @PathVariable int version, @CurrentUser User viewer) {
        return service.revision(id, version, viewer);
    }

    @PostMapping("/posts/{id}/revisions/{version}/restore")
    public PostDetail restore(@PathVariable Long id, @PathVariable int version, @CurrentUser User editor) {
        return service.restore(id, version, editor);
    }

    @GetMapping("/tags")
    public List<TagCount> tags(@RequestParam(defaultValue = "30") int limit) {
        return service.tags(limit);
    }
}
