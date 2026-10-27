package com.workblog.category;

import com.workblog.post.Post;
import com.workblog.post.PostService;
import com.workblog.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryAccessTest {

    private static User user(long id, String dept, User.Role role) {
        User u = new User("u" + id, "pw", "사용자" + id, null, dept, role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static Category cat(String name, Category parent, String... depts) {
        Category c = new Category(name, parent, 0);
        c.setRestrictedDepartments(List.of(depts));
        return c;
    }

    @Test
    void parse_and_join_normalize_trim_and_dedupe_case_insensitively() {
        assertThat(CategoryAccess.parse(" 운영팀, 인프라팀 ,,운영팀, Ops ,ops")).containsExactly("운영팀", "인프라팀", "Ops");
        assertThat(CategoryAccess.join(List.of("운영팀, 인프라팀", " ", "인프라팀"))).isEqualTo("운영팀,인프라팀");
        assertThat(CategoryAccess.parse("")).isEmpty();
        assertThat(CategoryAccess.parse(null)).isEmpty();
    }

    @Test
    void no_restriction_allows_everyone_even_without_department() {
        assertThat(CategoryAccess.allows(List.of(List.of(), List.of()), null)).isTrue();
    }

    @Test
    void restriction_requires_membership_ignoring_case_and_spaces() {
        assertThat(CategoryAccess.allows(List.of(List.of("인프라팀", "Ops")), " ops ")).isTrue();
        assertThat(CategoryAccess.allows(List.of(List.of("인프라팀")), "운영팀")).isFalse();
        assertThat(CategoryAccess.allows(List.of(List.of("인프라팀")), "")).isFalse();
        assertThat(CategoryAccess.allows(List.of(List.of("인프라팀")), null)).isFalse();
    }

    @Test
    void every_restricted_ancestor_must_be_satisfied() {
        assertThat(CategoryAccess.allows(List.of(List.of("인프라팀"), List.of("인프라팀", "운영팀")), "인프라팀")).isTrue();
        assertThat(CategoryAccess.allows(List.of(List.of("인프라팀"), List.of("운영팀")), "인프라팀")).isFalse();
    }

    @Test
    void restriction_on_parent_applies_to_children_and_admin_bypasses() {
        Category root = cat("서버 정보", null, "인프라팀");
        Category child = cat("AWS", root);
        assertThat(CategoryAccess.isRestricted(child)).isTrue();
        assertThat(CategoryAccess.allows(child, user(1, "인프라팀", User.Role.USER))).isTrue();
        assertThat(CategoryAccess.allows(child, user(2, "기획팀", User.Role.USER))).isFalse();
        assertThat(CategoryAccess.allows(child, user(3, null, User.Role.ADMIN))).isTrue();
        assertThat(CategoryAccess.isRestricted(cat("일반", null))).isFalse();
    }

    @Test
    void canView_restricted_post_member_author_admin_only() {
        User author = user(10, "기획팀", User.Role.USER);
        Category restricted = cat("AWS", cat("서버 정보", null, "인프라팀"));
        Post p = new Post("t", "c", Post.ContentFormat.HTML, null, restricted, List.of(), Post.Status.PUBLISHED, Post.Visibility.PUBLIC, author);
        assertThat(PostService.canView(p, author)).isTrue();
        assertThat(PostService.canView(p, user(11, "인프라팀", User.Role.USER))).isTrue();
        assertThat(PostService.canView(p, user(12, "기획팀", User.Role.USER))).isFalse();
        assertThat(PostService.canView(p, user(13, null, User.Role.ADMIN))).isTrue();

        Post open = new Post("t", "c", Post.ContentFormat.HTML, null, cat("일반", null), List.of(), Post.Status.PUBLISHED, Post.Visibility.PUBLIC, author);
        assertThat(PostService.canView(open, user(12, "기획팀", User.Role.USER))).isTrue();
        Post privateMember = new Post("t", "c", Post.ContentFormat.HTML, null, restricted, List.of(), Post.Status.PUBLISHED, Post.Visibility.PRIVATE, author);
        assertThat(PostService.canView(privateMember, user(11, "인프라팀", User.Role.USER))).isFalse();
    }
}
