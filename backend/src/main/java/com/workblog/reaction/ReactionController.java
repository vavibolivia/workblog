package com.workblog.reaction;

import com.workblog.auth.CurrentUser;
import com.workblog.reaction.ReactionDtos.LikeResponse;
import com.workblog.user.User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReactionController {
    private final ReactionService service;

    public ReactionController(ReactionService service) {
        this.service = service;
    }

    /** 공감 토글: 이미 공감했으면 취소. {liked, count} 반환. */
    @PostMapping("/posts/{id}/like")
    public LikeResponse toggle(@PathVariable Long id, @CurrentUser User user) {
        return service.toggle(id, user);
    }
}
