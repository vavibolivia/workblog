package com.workblog.comment;

import com.workblog.auth.CurrentUser;
import com.workblog.comment.CommentDtos.*;
import com.workblog.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
public class CommentController {
    private final CommentService service;

    public CommentController(CommentService service) {
        this.service = service;
    }

    /** 평면 목록(작성 순). parentId 로 답글을 묶는다. */
    @GetMapping
    public List<CommentResponse> list(@PathVariable Long postId, @CurrentUser User viewer) {
        return service.list(postId, viewer);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(@PathVariable Long postId, @Valid @RequestBody CommentRequest req, @CurrentUser User author) {
        return service.create(postId, req, author);
    }

    @PutMapping("/{id}")
    public CommentResponse update(@PathVariable Long postId, @PathVariable Long id,
                                  @Valid @RequestBody CommentUpdateRequest req, @CurrentUser User editor) {
        return service.update(postId, id, req, editor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long postId, @PathVariable Long id, @CurrentUser User editor) {
        service.delete(postId, id, editor);
    }
}
