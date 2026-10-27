package com.workblog.history;

import com.workblog.auth.CurrentUser;
import com.workblog.common.PageResponse;
import com.workblog.revision.PostRevisionRepository;
import com.workblog.revision.RevisionDtos.ActivityEntry;
import com.workblog.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 전사 활동 이력(History): 모든 글의 생성/수정/복원/삭제 기록을 시간순으로. */
@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final PostRevisionRepository revisions;

    public HistoryController(PostRevisionRepository revisions) {
        this.revisions = revisions;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<ActivityEntry> activity(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @CurrentUser User viewer) {
        var result = revisions.findActivity(viewer.getId(), viewer.isAdmin(), com.workblog.post.Post.Status.PUBLISHED, com.workblog.post.Post.Visibility.PUBLIC,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(result, ActivityEntry::from);
    }
}
