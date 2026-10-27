package com.workblog.category;

import com.workblog.category.CategoryDtos.CategoryNode;
import com.workblog.category.CategoryDtos.CreateCategoryRequest;
import com.workblog.category.CategoryDtos.UpdateCategoryRequest;
import com.workblog.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 순수 단위 테스트: Spring 컨텍스트 없이 CategoryRepository 를 Mockito 로 대체한다. */
class CategoryServiceTest {

    private CategoryRepository repo;
    private CategoryService service;
    private final AtomicLong seq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        repo = mock(CategoryRepository.class);
        service = new CategoryService(repo);
        // save() 는 id 를 부여해 같은 객체를 돌려준다
        when(repo.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            if (c.getId() == null) setId(c, seq.incrementAndGet());
            return c;
        });
    }

    // ---------- helpers ----------

    static void setId(Category c, long id) {
        try {
            Field f = Category.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** id 를 가진 노드 하나를 만들고 path 를 확정한 뒤 findById 응답에 등록한다. */
    private Category node(long id, String name, Category parent) {
        Category c = new Category(name, parent, 1);
        setId(c, id);
        c.refreshPath();
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    /** 루트부터 depth 단계까지 이어진 체인(id 1..depth)을 만들고 말단 노드를 돌려준다. */
    private Category chain(int depth) {
        Category cur = null;
        for (int d = 1; d <= depth; d++) cur = node(d, "L" + d, cur);
        return cur;
    }

    private static void assertApi(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).getStatus()).isEqualTo(status);
    }

    // ---------- entity ----------

    @Test
    void refreshPath_builds_ancestor_path_and_depth() {
        Category leaf = chain(3);
        assertThat(leaf.getDepth()).isEqualTo(3);
        assertThat(leaf.getPath()).isEqualTo("/1/2/3/");
        assertThat(leaf.getParent().getPath()).isEqualTo("/1/2/");
        assertThat(CategoryService.pathText(leaf)).isEqualTo("L1 > L2 > L3");
        assertThat(CategoryService.namePath(leaf)).containsExactly("L1", "L2", "L3");
    }

    // ---------- create ----------

    @Test
    void create_root_has_depth_1_and_next_sort_order() {
        when(repo.existsSibling(isNull(), eq("기술"), isNull())).thenReturn(false);
        when(repo.maxSortOrder(isNull())).thenReturn(4);

        CategoryNode n = service.create(new CreateCategoryRequest("기술", null));

        assertThat(n.depth()).isEqualTo(1);
        assertThat(n.parentId()).isNull();
        assertThat(n.sortOrder()).isEqualTo(5);
        assertThat(n.id()).isNotNull();
    }

    @Test
    void create_at_depth_5_is_allowed() {
        Category parent = chain(4);
        when(repo.existsSibling(eq(4L), anyString(), isNull())).thenReturn(false);
        when(repo.maxSortOrder(4L)).thenReturn(0);

        CategoryNode n = service.create(new CreateCategoryRequest("L5", 4L));

        assertThat(n.depth()).isEqualTo(5);
        assertThat(n.parentId()).isEqualTo(parent.getId());
        verify(repo).save(any(Category.class));
    }

    @Test
    void create_at_depth_6_is_rejected_with_400_before_saving() {
        chain(5);

        assertThatThrownBy(() -> service.create(new CreateCategoryRequest("L6", 5L)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("5단계")
                .hasMessageContaining("L1 > L2 > L3 > L4 > L5");
        verify(repo, never()).save(any());
    }

    @Test
    void create_duplicate_sibling_name_is_rejected_with_409() {
        chain(1);
        when(repo.existsSibling(eq(1L), eq("중복"), isNull())).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateCategoryRequest("중복", 1L)))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT));
        verify(repo, never()).save(any());
    }

    @Test
    void create_name_is_trimmed_and_inner_whitespace_collapsed() {
        when(repo.maxSortOrder(isNull())).thenReturn(0);

        CategoryNode n = service.create(new CreateCategoryRequest("  엔진   정비  ", null));

        assertThat(n.name()).isEqualTo("엔진 정비");
        verify(repo).existsSibling(isNull(), eq("엔진 정비"), isNull());
    }

    @Test
    void create_name_containing_gt_is_rejected_with_400() {
        assertThatThrownBy(() -> service.create(new CreateCategoryRequest("정비>엔진", null)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining(">");
        verify(repo, never()).save(any());
    }

    @Test
    void create_blank_or_null_name_is_rejected_with_400() {
        assertThatThrownBy(() -> service.create(new CreateCategoryRequest("   ", null)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.create(new CreateCategoryRequest(null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST));
        verify(repo, never()).save(any());
    }

    @Test
    void create_unknown_parent_is_404() {
        when(repo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new CreateCategoryRequest("x", 999L)))
                .satisfies(t -> assertApi(t, HttpStatus.NOT_FOUND));
    }

    // ---------- update / move ----------

    @Test
    void update_rename_without_move_keeps_parent_and_applies_sort_order() {
        Category root = node(1, "A", null);
        Category c = node(2, "B", root);
        when(repo.existsSibling(eq(1L), eq("B2"), eq(2L))).thenReturn(false);

        CategoryNode n = service.update(2L, new UpdateCategoryRequest("B2", null, null, 7));

        assertThat(n.name()).isEqualTo("B2");
        assertThat(n.parentId()).isEqualTo(1L);
        assertThat(n.sortOrder()).isEqualTo(7);
        assertThat(c.getPath()).isEqualTo("/1/2/");
        verify(repo, never()).findSubtree(anyString());
    }

    @Test
    void update_rename_to_existing_sibling_name_is_409() {
        Category root = node(1, "A", null);
        node(2, "B", root);
        when(repo.existsSibling(eq(1L), eq("C"), eq(2L))).thenReturn(true);

        assertThatThrownBy(() -> service.update(2L, new UpdateCategoryRequest("C", null, null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT));
    }

    @Test
    void update_move_under_own_descendant_is_400() {
        Category root = node(1, "A", null);
        Category child = node(2, "B", root);
        node(3, "C", child);

        // A(1) 를 손자 C(3) 아래로
        assertThatThrownBy(() -> service.update(1L, new UpdateCategoryRequest("A", 3L, null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("하위 카테고리 아래로");
        assertThat(root.getParent()).isNull();
        assertThat(root.getPath()).isEqualTo("/1/");
    }

    @Test
    void update_move_under_itself_is_400() {
        node(1, "A", null);
        assertThatThrownBy(() -> service.update(1L, new UpdateCategoryRequest("A", 1L, null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_move_that_would_exceed_depth_5_is_400() {
        // 옮길 트리: X(10) - Y(11) - Z(12), 높이 3
        Category x = node(10, "X", null);
        Category y = node(11, "Y", x);
        Category z = node(12, "Z", y);
        when(repo.findSubtree("/10/")).thenReturn(List.of(x, y, z));
        // 새 부모: depth 3 → 새 depth 4, 4 + 3 - 1 = 6 > 5
        chain(3);

        assertThatThrownBy(() -> service.update(10L, new UpdateCategoryRequest("X", 3L, null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("6단계")
                .hasMessageContaining("3단계");
        assertThat(x.getParent()).isNull();
        assertThat(x.getPath()).isEqualTo("/10/");
        assertThat(z.getPath()).isEqualTo("/10/11/12/");
    }

    @Test
    void update_move_that_lands_exactly_on_depth_5_is_allowed_and_subtree_paths_are_recomputed() {
        Category x = node(10, "X", null);
        Category y = node(11, "Y", x);
        Category z = node(12, "Z", y);
        when(repo.findSubtree("/10/")).thenReturn(List.of(x, y, z));
        chain(2); // 새 부모 depth 2 → 2 + 1 + 3 - 1 = 5
        when(repo.maxSortOrder(2L)).thenReturn(3);

        CategoryNode n = service.update(10L, new UpdateCategoryRequest("X", 2L, null, 99));

        assertThat(n.parentId()).isEqualTo(2L);
        assertThat(n.depth()).isEqualTo(3);
        // 이동 시 sortOrder 는 요청값이 아니라 새 부모의 끝
        assertThat(n.sortOrder()).isEqualTo(4);
        assertThat(x.getPath()).isEqualTo("/1/2/10/");
        assertThat(y.getPath()).isEqualTo("/1/2/10/11/");
        assertThat(y.getDepth()).isEqualTo(4);
        assertThat(z.getPath()).isEqualTo("/1/2/10/11/12/");
        assertThat(z.getDepth()).isEqualTo(5);
    }

    @Test
    void update_moveToRoot_detaches_and_resets_path() {
        Category root = node(1, "A", null);
        Category c = node(2, "B", root);
        Category g = node(3, "C", c);
        when(repo.findSubtree("/1/2/")).thenReturn(List.of(c, g));
        when(repo.maxSortOrder(isNull())).thenReturn(1);

        CategoryNode n = service.update(2L, new UpdateCategoryRequest("B", 1L, true, null));

        assertThat(n.parentId()).isNull();
        assertThat(n.depth()).isEqualTo(1);
        assertThat(c.getPath()).isEqualTo("/2/");
        assertThat(g.getPath()).isEqualTo("/2/3/");
        assertThat(g.getDepth()).isEqualTo(2);
    }

    @Test
    void update_sibling_check_uses_the_new_parent_when_moving() {
        Category a = node(1, "A", null);
        node(2, "B", null);
        Category c = node(3, "C", a);
        when(repo.existsSibling(eq(2L), eq("C"), eq(3L))).thenReturn(true);

        assertThatThrownBy(() -> service.update(3L, new UpdateCategoryRequest("C", 2L, null, null)))
                .satisfies(t -> assertApi(t, HttpStatus.CONFLICT));
        assertThat(c.getParent()).isSameAs(a);
    }

    // ---------- delete ----------

    @Test
    void delete_with_children_is_400() {
        Category c = node(1, "A", null);
        when(repo.existsByParentId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("하위 카테고리");
        verify(repo, never()).delete(c);
    }

    @Test
    void delete_with_linked_posts_including_deleted_ones_is_400() {
        Category c = node(1, "A", null);
        when(repo.existsByParentId(1L)).thenReturn(false);
        when(repo.countAllPosts(1L)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(1L))
                .satisfies(t -> assertApi(t, HttpStatus.BAD_REQUEST))
                .hasMessageContaining("글");
        verify(repo, never()).delete(c);
    }

    @Test
    void delete_default_root_general_allowed_when_empty() {
        Category c = node(1, Category.DEFAULT_NAME, null);
        when(repo.existsByParentId(1L)).thenReturn(false);
        when(repo.countAllPosts(1L)).thenReturn(0L);

        service.delete(1L);
        verify(repo).delete(c);
    }

    @Test
    void delete_non_root_named_general_is_allowed() {
        Category root = node(1, "A", null);
        Category c = node(2, Category.DEFAULT_NAME, root);
        when(repo.existsByParentId(2L)).thenReturn(false);
        when(repo.countAllPosts(2L)).thenReturn(0L);

        service.delete(2L);

        verify(repo).delete(c);
    }

    @Test
    void delete_empty_leaf_succeeds() {
        Category c = node(1, "A", null);
        when(repo.existsByParentId(1L)).thenReturn(false);
        when(repo.countAllPosts(1L)).thenReturn(0L);

        service.delete(1L);

        verify(repo).delete(c);
    }

    @Test
    void delete_unknown_id_is_404() {
        when(repo.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(42L)).satisfies(t -> assertApi(t, HttpStatus.NOT_FOUND));
    }

    // ---------- resolveForPost ----------

    @Test
    void resolveForPost_returns_existing_default_root_when_no_category_given() {
        Category general = node(1, Category.DEFAULT_NAME, null);
        when(repo.findRootByName(Category.DEFAULT_NAME)).thenReturn(Optional.of(general));

        assertThat(service.resolveForPost(null)).isSameAs(general);
        verify(repo, never()).save(any());
    }

    @Test
    void resolveForPost_creates_default_root_when_missing() {
        when(repo.findRootByName(Category.DEFAULT_NAME)).thenReturn(Optional.empty());

        Category c = service.resolveForPost(null);

        assertThat(c.getName()).isEqualTo(Category.DEFAULT_NAME);
        assertThat(c.getDepth()).isEqualTo(1);
        assertThat(c.getId()).isNotNull();
        assertThat(c.getPath()).isEqualTo("/" + c.getId() + "/");
        verify(repo).save(any(Category.class));
    }

    @Test
    void resolveForPost_returns_requested_category_when_given() {
        Category c = node(5, "기술", null);
        assertThat(service.resolveForPost(5L)).isSameAs(c);
    }
}
