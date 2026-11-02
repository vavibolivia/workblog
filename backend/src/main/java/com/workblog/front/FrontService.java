package com.workblog.front;

import com.workblog.common.ApiException;
import com.workblog.post.Post;
import com.workblog.post.PostRepository;
import com.workblog.post.PostService;
import com.workblog.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 대문(홈 맨 위). 관리자가 고른 글 · 공수 표를 순서대로 탭으로 띄운다.
 * 읽을 때 보는 사람 권한으로 거른다(열람 제한·비공개·휴지통 글, 지워진 표는 빠짐).
 */
@Service
@Transactional
public class FrontService {
    public enum Kind { POST, MM_SHEET }

    public record FrontItem(Kind kind, long refId, String title) {}

    public record ItemRef(Kind kind, Long refId) {}

    private final JdbcTemplate jdbc;
    private final PostRepository posts;

    public FrontService(JdbcTemplate jdbc, PostRepository posts) {
        this.jdbc = jdbc;
        this.posts = posts;
    }

    @Transactional(readOnly = true)
    public List<FrontItem> list(User viewer) {
        List<FrontItem> out = new ArrayList<>();
        for (ItemRef r : refs()) {
            title(r, viewer).ifPresent(t -> out.add(new FrontItem(r.kind(), r.refId(), t)));
        }
        return out;
    }

    /** 대문 항목을 통째로 바꾼다(준 순서 = 탭 순서). 관리자만. */
    public List<FrontItem> replace(List<ItemRef> items, User actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("대문은 관리자만 고를 수 있습니다.");
        List<ItemRef> unique = new ArrayList<>(new LinkedHashSet<>(items));
        if (unique.size() > 30) throw ApiException.badRequest("대문 항목은 30개까지입니다.");
        for (ItemRef r : unique) {
            if (r.kind() == null || r.refId() == null) throw ApiException.badRequest("항목 종류와 id 가 필요합니다.");
            // 대문에는 글만 올린다(공수 표·발표 자료는 그것을 담은 글로). MM_SHEET 는 예전 데이터 호환용으로만 남김.
            if (r.kind() != Kind.POST) throw ApiException.badRequest("대문에는 글만 올릴 수 있습니다. 공수 표는 [글로 게시] 로 글을 만든 뒤 올리세요.");
            if (title(r, actor).isEmpty()) throw ApiException.badRequest("없거나 삭제된 항목입니다: " + r.kind() + " " + r.refId());
        }
        jdbc.update("DELETE FROM front_items");
        for (int i = 0; i < unique.size(); i++) {
            jdbc.update("INSERT INTO front_items (kind, ref_id, sort_order) VALUES (?,?,?)", unique.get(i).kind().name(), unique.get(i).refId(), i + 1);
        }
        return list(actor);
    }

    private List<ItemRef> refs() {
        return jdbc.query("SELECT kind, ref_id FROM front_items ORDER BY sort_order, id",
                (rs, i) -> new ItemRef(Kind.valueOf(rs.getString(1)), rs.getLong(2)));
    }

    /** 보는 사람이 볼 수 있으면 제목. */
    private Optional<String> title(ItemRef r, User viewer) {
        if (r.kind() == Kind.POST) {
            return posts.findById(r.refId()).filter(p -> !p.isDeleted() && PostService.canView(p, viewer)).map(Post::getTitle);
        }
        List<String> t = jdbc.queryForList("SELECT title FROM mm_sheets WHERE id = ?", String.class, r.refId());
        return t.stream().findFirst();
    }
}
