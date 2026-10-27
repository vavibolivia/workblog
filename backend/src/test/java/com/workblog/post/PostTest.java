package com.workblog.post;

import com.workblog.category.Category;
import com.workblog.post.Post.ContentFormat;
import com.workblog.post.Post.Status;
import com.workblog.post.Post.Visibility;
import com.workblog.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostTest {

    private static final Category CAT = new Category("일반", null, 0);
    private static final User AUTHOR = new User("u", "pw", "사용자", null, null, User.Role.USER);

    private static Post post(ContentFormat fmt, List<String> tags, Status status, Visibility vis) {
        return new Post("t", "c", fmt, null, CAT, tags, status, vis, AUTHOR);
    }

    // ---------- tags ----------

    @Test
    void joinTags_trims_collapses_whitespace_and_dedupes() {
        String joined = Post.joinTags(Arrays.asList(" spring ", "spring", "  java   boot ", null, "", "   "));
        assertThat(joined).isEqualTo("spring,java boot");
    }

    @Test
    void joinTags_replaces_commas_inside_a_tag_with_space() {
        assertThat(Post.joinTags(List.of("a,b", "c,,d"))).isEqualTo("a b,c d");
    }

    @Test
    void joinTags_null_or_empty_gives_empty_string() {
        assertThat(Post.joinTags(null)).isEmpty();
        assertThat(Post.joinTags(List.of())).isEmpty();
    }

    @Test
    void joinTags_is_case_sensitive_for_dedupe() {
        // 현재 구현은 소문자화하지 않는다 (필드 주석의 'lower-case' 와 다름)
        assertThat(Post.joinTags(List.of("Java", "java"))).isEqualTo("Java,java");
    }

    @Test
    void splitTags_trims_and_drops_empties() {
        assertThat(Post.splitTags(" a , ,b ,, c")).containsExactly("a", "b", "c");
    }

    @Test
    void splitTags_null_or_blank_gives_empty_list() {
        assertThat(Post.splitTags(null)).isEmpty();
        assertThat(Post.splitTags("  ")).isEmpty();
    }

    @Test
    void join_then_split_round_trips() {
        List<String> in = List.of("spring", "java boot", "db");
        assertThat(Post.splitTags(Post.joinTags(in))).containsExactlyElementsOf(in);
    }

    @Test
    void constructor_normalizes_tags_and_exposes_tag_list() {
        Post p = post(ContentFormat.HTML, Arrays.asList(" x ", "x", "y,z"), Status.DRAFT, null);
        assertThat(p.getTags()).isEqualTo("x,y z");
        assertThat(p.getTagList()).containsExactly("x", "y z");
    }

    // ---------- lifecycle ----------

    @Test
    void draft_has_no_publishedAt_and_published_has_one() {
        Post draft = post(null, null, Status.DRAFT, null);
        Post pub = post(null, null, Status.PUBLISHED, null);
        assertThat(draft.getPublishedAt()).isNull();
        assertThat(pub.getPublishedAt()).isNotNull();
        assertThat(draft.getVersion()).isEqualTo(1);
    }

    @Test
    void constructor_defaults_when_null() {
        Post p = post(null, null, null, null);
        assertThat(p.getContentFormat()).isEqualTo(ContentFormat.HTML);
        assertThat(p.getStatus()).isEqualTo(Status.PUBLISHED);
        assertThat(p.getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void isOpen_requires_published_and_public() {
        assertThat(post(null, null, Status.PUBLISHED, Visibility.PUBLIC).isOpen()).isTrue();
        assertThat(post(null, null, Status.PUBLISHED, Visibility.PRIVATE).isOpen()).isFalse();
        assertThat(post(null, null, Status.DRAFT, Visibility.PUBLIC).isOpen()).isFalse();
    }

    @Test
    void applyEdit_bumps_version_and_replaces_fields() {
        Post p = new Post("t", "c", ContentFormat.HTML, "s", CAT, List.of("a"), Status.DRAFT, Visibility.PUBLIC, AUTHOR);
        Category other = new Category("기술", null, 1);
        p.applyEdit("t2", "c2", ContentFormat.MARKDOWN, "s2", other, List.of("b", "c"), null, null);
        assertThat(p.getVersion()).isEqualTo(2);
        assertThat(p.getTitle()).isEqualTo("t2");
        assertThat(p.getContent()).isEqualTo("c2");
        assertThat(p.getContentFormat()).isEqualTo(ContentFormat.MARKDOWN);
        assertThat(p.getSummary()).isEqualTo("s2");
        assertThat(p.getCategory()).isSameAs(other);
        assertThat(p.getTagList()).containsExactly("b", "c");
        // status / visibility null → 유지
        assertThat(p.getStatus()).isEqualTo(Status.DRAFT);
        assertThat(p.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(p.getPublishedAt()).isNull();
    }

    @Test
    void applyEdit_null_format_keeps_existing_format() {
        Post p = post(ContentFormat.MARKDOWN, null, Status.DRAFT, null);
        p.applyEdit("t", "c", null, null, CAT, null, null, null);
        assertThat(p.getContentFormat()).isEqualTo(ContentFormat.MARKDOWN);
    }

    @Test
    void applyEdit_changes_visibility_when_given() {
        Post p = post(null, null, Status.PUBLISHED, Visibility.PUBLIC);
        p.applyEdit("t", "c", null, null, CAT, null, null, Visibility.PRIVATE);
        assertThat(p.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(p.isOpen()).isFalse();
    }

    @Test
    void applyEdit_sets_publishedAt_once_on_first_publish_and_keeps_it_afterwards() {
        Post p = post(null, null, Status.DRAFT, null);
        assertThat(p.getPublishedAt()).isNull();

        p.applyEdit("t", "c", null, null, CAT, null, Status.PUBLISHED, null);
        Instant first = p.getPublishedAt();
        assertThat(first).isNotNull();
        assertThat(p.getStatus()).isEqualTo(Status.PUBLISHED);
        assertThat(p.getVersion()).isEqualTo(2);

        p.applyEdit("t", "c", null, null, CAT, null, Status.PUBLISHED, null);
        assertThat(p.getPublishedAt()).isSameAs(first);
        assertThat(p.getVersion()).isEqualTo(3);

        // 다시 DRAFT 로 내려도 최초 발행 시각은 유지
        p.applyEdit("t", "c", null, null, CAT, null, Status.DRAFT, null);
        assertThat(p.getStatus()).isEqualTo(Status.DRAFT);
        assertThat(p.getPublishedAt()).isSameAs(first);
        assertThat(p.getVersion()).isEqualTo(4);
    }

    @Test
    void softDelete_and_restore_bump_version() {
        Post p = post(null, null, Status.PUBLISHED, null);
        p.softDelete();
        assertThat(p.isDeleted()).isTrue();
        assertThat(p.getDeletedAt()).isNotNull();
        assertThat(p.getVersion()).isEqualTo(2);
        p.restoreFromDelete();
        assertThat(p.isDeleted()).isFalse();
        assertThat(p.getVersion()).isEqualTo(3);
    }

    @Test
    void incrementViews_counts_up() {
        Post p = post(null, null, Status.PUBLISHED, null);
        p.incrementViews();
        p.incrementViews();
        assertThat(p.getViewCount()).isEqualTo(2);
    }
}
