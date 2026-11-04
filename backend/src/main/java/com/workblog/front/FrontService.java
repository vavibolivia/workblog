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
        // 대문에는 글만 올린다(공수 표는 그것을 담은 글로). 예전 MM_SHEET 항목은 화면이 목록을 통째로 다시 보내도 막히지 않게 조용히 뺀다.
        unique.removeIf(r -> r.kind() == Kind.MM_SHEET);
        for (ItemRef r : unique) {
            if (r.kind() == null || r.refId() == null) throw ApiException.badRequest("항목 종류와 id 가 필요합니다.");
            if (title(r, actor).isEmpty()) throw ApiException.badRequest("없거나 삭제된 항목입니다: " + r.kind() + " " + r.refId());
        }
        // 휴지통에 간 글은 목록에 안 보여 화면이 다시 보내지 않는다 → 지우지 말고 뒤에 남겨 두어 복구하면 대문에 돌아오게
        List<Long> keep = jdbc.queryForList("SELECT f.ref_id FROM front_items f JOIN posts p ON p.id = f.ref_id WHERE f.kind = 'POST' AND p.deleted_at IS NOT NULL ORDER BY f.sort_order", Long.class);
        jdbc.update("DELETE FROM front_items");
        int i = 0;
        for (ItemRef r : unique) jdbc.update("INSERT INTO front_items (kind, ref_id, sort_order) VALUES (?,?,?)", r.kind().name(), r.refId(), ++i);
        for (Long id : keep) if (unique.stream().noneMatch(r -> r.refId().equals(id))) jdbc.update("INSERT INTO front_items (kind, ref_id, sort_order) VALUES ('POST',?,?)", id, ++i);
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
