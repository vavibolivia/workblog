package com.workblog.category;

import com.workblog.category.CategoryDtos.*;
import com.workblog.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 카테고리 트리 관리. 생성·이동 모두 최대 깊이 {@link Category#MAX_DEPTH} 를 넘지 못한다.
 * 이동 시: 새 부모 깊이 + 옮기는 하위 트리의 높이 ≤ 5, 자기 자신/자손 아래로는 이동 불가.
 */
@Service
@Transactional
public class CategoryService {
    private final CategoryRepository repo;

    public CategoryService(CategoryRepository repo) {
        this.repo = repo;
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public List<CategoryNode> tree() {
        List<Category> all = repo.findAllOrdered();
        Map<Long, Long> direct = new HashMap<>();
        for (Object[] r : repo.publishedCounts()) direct.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        Map<Long, List<Category>> byParent = new HashMap<>();
        for (Category c : all) byParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        return build(null, byParent, direct);
    }

    private List<CategoryNode> build(Long parentId, Map<Long, List<Category>> byParent, Map<Long, Long> direct) {
        List<CategoryNode> out = new ArrayList<>();
        for (Category c : byParent.getOrDefault(parentId, List.of())) {
            List<CategoryNode> kids = build(c.getId(), byParent, direct);
            long own = direct.getOrDefault(c.getId(), 0L);
            long total = own + kids.stream().mapToLong(CategoryNode::totalCount).sum();
            out.add(new CategoryNode(c.getId(), c.getName(), c.getParentId(), c.getDepth(), c.getSortOrder(), own, total, kids, c.getRestrictedDepartmentList()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public boolean existsId(Long id) {
        return id != null && repo.existsById(id);
    }

    @Transactional(readOnly = true)
    public Category get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("카테고리"));
    }

    /**
     * 글 저장 시 카테고리 결정. 지정이 있으면 그것.
     * 없으면 최상위 '일반', '일반'이 삭제됐으면 최상위 첫 카테고리(순서 기준). 카테고리가 하나도 없을 때만 '일반'을 만든다.
     * (2026-10-27 지시 "기본 카테고리 일반 삭제 가능하도록")
     */
    public Category resolveForPost(Long categoryId) {
        if (categoryId != null) return get(categoryId);
        return repo.findRootByName(Category.DEFAULT_NAME)
                .or(() -> repo.findAllOrdered().stream().filter(c -> c.getParent() == null).findFirst())
                .orElseGet(() -> {
                    Category c = repo.save(new Category(Category.DEFAULT_NAME, null, 0));
                    c.refreshPath();
                    return c;
                });
    }

    /** "서버 정보 > AWS" 또는 "서버 정보/AWS" 형태의 이름 경로로 카테고리를 찾는다(대소문자 무시). 없으면 400. */
    @Transactional(readOnly = true)
    public Category findByPathText(String pathText) {
        List<String> want = java.util.Arrays.stream(pathText.split("\s*[>/]\s*")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (want.isEmpty()) throw ApiException.badRequest("카테고리 경로가 비어 있습니다.");
        for (Category c : repo.findAllOrdered()) {
            List<String> have = namePath(c);
            if (have.size() != want.size()) continue;
            boolean same = true;
            for (int i = 0; i < have.size() && same; i++) same = have.get(i).equalsIgnoreCase(want.get(i));
            if (same) return c;
        }
        throw ApiException.badRequest("카테고리를 찾을 수 없습니다: " + String.join(" > ", want) + " (GET /api/categories 로 트리를 확인하세요)");
    }

    @Transactional(readOnly = true)
    public CategoryRef ref(Category c) {
        LinkedList<Long> ids = new LinkedList<>();
        for (Category cur = c; cur != null; cur = cur.getParent()) ids.addFirst(cur.getId());
        return new CategoryRef(c.getId(), c.getName(), namePath(c), ids);
    }

    public static List<String> namePath(Category c) {
        LinkedList<String> names = new LinkedList<>();
        for (Category cur = c; cur != null; cur = cur.getParent()) names.addFirst(cur.getName());
        return names;
    }

    public static String pathText(Category c) {
        return String.join(" > ", namePath(c));
    }

    // ---------- commands ----------

    public CategoryNode create(CreateCategoryRequest req) {
        String name = normalize(req.name());
        Category parent = req.parentId() == null ? null : get(req.parentId());
        int depth = parent == null ? 1 : parent.getDepth() + 1;
        if (depth > Category.MAX_DEPTH) {
            throw ApiException.badRequest("카테고리는 최대 " + Category.MAX_DEPTH + "단계까지 만들 수 있습니다. '"
                    + pathText(parent) + "' 아래에는 더 만들 수 없습니다.");
        }
        if (repo.existsSibling(req.parentId(), name, null)) {
            throw ApiException.conflict("같은 위치에 '" + name + "' 카테고리가 이미 있습니다.");
        }
        Category c = repo.save(new Category(name, parent, repo.maxSortOrder(req.parentId()) + 1));
        c.refreshPath();
        return new CategoryNode(c.getId(), c.getName(), c.getParentId(), c.getDepth(), c.getSortOrder(), 0, 0, List.of());
    }

    public CategoryNode update(Long id, UpdateCategoryRequest req) {
        Category c = get(id);
        String name = normalize(req.name());

        boolean toRoot = Boolean.TRUE.equals(req.moveToRoot());
        Long newParentId = toRoot ? null : (req.parentId() != null ? req.parentId() : c.getParentId());
        boolean moving = !Objects.equals(newParentId, c.getParentId());

        if (repo.existsSibling(newParentId, name, c.getId())) {
            throw ApiException.conflict("같은 위치에 '" + name + "' 카테고리가 이미 있습니다.");
        }

        if (moving) {
            Category newParent = newParentId == null ? null : get(newParentId);
            if (newParent != null && newParent.getPath().startsWith(c.getPath())) {
                throw ApiException.badRequest("카테고리를 자기 자신이나 하위 카테고리 아래로 옮길 수 없습니다.");
            }
            List<Category> subtree = repo.findSubtree(c.getPath());
            int height = subtree.stream().mapToInt(Category::getDepth).max().orElse(c.getDepth()) - c.getDepth() + 1;
            int newDepth = newParent == null ? 1 : newParent.getDepth() + 1;
            if (newDepth + height - 1 > Category.MAX_DEPTH) {
                throw ApiException.badRequest("옮기면 " + (newDepth + height - 1) + "단계가 되어 최대 " + Category.MAX_DEPTH
                        + "단계를 넘습니다. (옮기는 카테고리는 하위 포함 " + height + "단계)");
            }
            c.setParent(newParent);
            c.setSortOrder(repo.maxSortOrder(newParentId) + 1);
            // 하위 트리 depth/path 재계산: 얕은 것부터
            c.refreshPath();
            subtree.stream().filter(s -> !s.getId().equals(c.getId()))
                    .sorted(Comparator.comparingInt(Category::getDepth))
                    .forEach(Category::refreshPath);
        }
        c.setName(name);
        if (req.sortOrder() != null && !moving) c.setSortOrder(req.sortOrder());
        if (req.restrictedDepartments() != null) {
            String joined = CategoryAccess.join(req.restrictedDepartments());
            if (joined.length() > 1000) throw ApiException.badRequest("열람 제한 부서 목록이 너무 깁니다(최대 1000자).");
            c.setRestrictedDepartments(req.restrictedDepartments());
        }
        return new CategoryNode(c.getId(), c.getName(), c.getParentId(), c.getDepth(), c.getSortOrder(), 0, 0, List.of(), c.getRestrictedDepartmentList());
    }

    public void delete(Long id) {
        Category c = get(id);
        if (repo.existsByParentId(id)) throw ApiException.badRequest("하위 카테고리가 있어 삭제할 수 없습니다. 하위 카테고리를 먼저 옮기거나 삭제하세요.");
        if (repo.countAllPosts(id) > 0) throw ApiException.badRequest("이 카테고리에 연결된 글이 있어 삭제할 수 없습니다. 글을 다른 카테고리로 옮긴 뒤 삭제하세요.");
        repo.delete(c);
    }

    /** 같은 부모 안에서 순서 한 칸 이동. direction = -1(위) / 1(아래) */
    /** 끌어 놓기: 필요하면 부모를 옮기고(update 의 검사 그대로), 새 형제 목록의 index 자리에 끼워 순서를 다시 매긴다. */
    public void place(Long id, Long parentId, int index) {
        Category c = get(id);
        if (!Objects.equals(parentId, c.getParentId())) {
            update(id, new UpdateCategoryRequest(c.getName(), parentId, parentId == null, null));
        }
        List<Category> siblings = new ArrayList<>(repo.findAllOrdered().stream()
                .filter(s -> Objects.equals(s.getParentId(), parentId) && !s.getId().equals(id)).toList());
        siblings.add(Math.max(0, Math.min(index, siblings.size())), c);
        for (int i = 0; i < siblings.size(); i++) siblings.get(i).setSortOrder(i + 1);
    }

    public void reorder(Long id, int direction) {
        Category c = get(id);
        List<Category> siblings = repo.findAllOrdered().stream()
                .filter(s -> Objects.equals(s.getParentId(), c.getParentId())).toList();
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) if (siblings.get(i).getId().equals(id)) idx = i;
        int target = idx + (direction < 0 ? -1 : 1);
        if (idx < 0 || target < 0 || target >= siblings.size()) return;
        List<Category> list = new ArrayList<>(siblings);
        Collections.swap(list, idx, target);
        for (int i = 0; i < list.size(); i++) list.get(i).setSortOrder(i + 1);
    }

    private static String normalize(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw ApiException.badRequest("카테고리 이름을 입력하세요.");
        if (n.contains(">")) throw ApiException.badRequest("카테고리 이름에 '>' 는 쓸 수 없습니다.");
        return n;
    }
}
