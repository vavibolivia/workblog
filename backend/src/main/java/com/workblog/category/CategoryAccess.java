package com.workblog.category;

import com.workblog.user.User;

import java.util.*;

/**
 * 카테고리 열람 제한(부서 그룹 권한). 서버 정보처럼 민감한 글을 특정 부서에만 보이게 할 때 쓴다.
 * <ul>
 *   <li>카테고리의 restrictedDepartments 가 비어 있으면 제한 없음.</li>
 *   <li>제한은 하위 카테고리에도 적용된다. 경로 위에 제한 카테고리가 여럿이면 <b>모두</b> 통과해야 한다.</li>
 *   <li>부서 비교는 앞뒤 공백을 빼고 대소문자를 무시한다(SQL 의 LOWER(TRIM()) 과 같다). 부서가 비어 있는 사용자는 제한 카테고리를 볼 수 없다.</li>
 *   <li>관리자는 항상 볼 수 있다. 글 작성자 예외는 호출하는 쪽(PostService.canView)이 처리한다.</li>
 * </ul>
 * SQL 쪽 같은 규칙: {@code PostRepository.RESTRICT_OK}, {@link #sqlAllowed(String, long)}, {@code PostRevisionRepository.findActivity}.
 */
public final class CategoryAccess {
    private CategoryAccess() {}

    public static final int MAX_DEPARTMENTS = 30;

    /** 카테고리(와 조상)의 제한을 viewer 가 통과하는가. 관리자는 항상 true. */
    public static boolean allows(Category category, User viewer) {
        if (viewer != null && viewer.isAdmin()) return true;
        List<List<String>> restrictions = new ArrayList<>();
        for (Category cur = category; cur != null; cur = cur.getParent()) restrictions.add(cur.getRestrictedDepartmentList());
        return allows(restrictions, viewer == null ? null : viewer.getDepartment());
    }

    /** 순수 판정: 경로 위 각 카테고리의 부서 목록(빈 목록 = 제한 없음)을 모두 통과하는가. */
    public static boolean allows(List<List<String>> restrictionsOnPath, String department) {
        String dept = key(department);
        for (List<String> r : restrictionsOnPath) {
            if (r == null || r.isEmpty()) continue;
            if (dept.isEmpty()) return false;
            boolean member = false;
            for (String d : r) if (key(d).equals(dept)) { member = true; break; }
            if (!member) return false;
        }
        return true;
    }

    /** 경로 위에 제한 카테고리가 하나라도 있는가. */
    public static boolean isRestricted(Category category) {
        for (Category cur = category; cur != null; cur = cur.getParent()) if (!cur.getRestrictedDepartmentList().isEmpty()) return true;
        return false;
    }

    /** "운영팀, 인프라팀" → [운영팀, 인프라팀]. 빈 항목·중복(대소문자 무시)은 뺀다. */
    public static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return normalize(Arrays.asList(csv.split(",")));
    }

    public static List<String> normalize(Collection<String> departments) {
        if (departments == null) return List.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String d : departments) {
            if (d == null) continue;
            String t = d.trim();
            if (t.isEmpty()) continue;
            out.putIfAbsent(key(t), t);
        }
        return List.copyOf(out.values());
    }

    /** 저장 형식(쉼표 구분). 쉼표가 든 부서명은 쪼개진다. */
    public static String join(Collection<String> departments) {
        List<String> flat = new ArrayList<>();
        if (departments != null) for (String d : departments) if (d != null) flat.addAll(Arrays.asList(d.split(",")));
        List<String> n = normalize(flat);
        return String.join(",", n);
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 네이티브 SQL 조각: posts 별칭 alias 의 글이 viewerId 사용자에게 제한으로 막히지 않으면 참.
     * 작성자 · 관리자 예외 포함. viewerId 는 숫자라 그대로 넣는다(부서는 users 테이블에서 읽는다).
     */
    public static String sqlAllowed(String alias, long viewerId) {
        return " (" + alias + ".author_id = " + viewerId + " OR NOT EXISTS (SELECT 1 FROM categories pc JOIN categories rc ON pc.path LIKE CONCAT(rc.path, '%') " +
               " WHERE pc.id = " + alias + ".category_id AND rc.restricted_departments <> '' " +
               " AND NOT EXISTS (SELECT 1 FROM users vu WHERE vu.id = " + viewerId + " AND (vu.role = 'ADMIN' OR (COALESCE(TRIM(vu.department), '') <> '' " +
               " AND POSITION(CONCAT(',', LOWER(TRIM(vu.department)), ',') IN CONCAT(',', LOWER(rc.restricted_departments), ',')) > 0))))) ";
    }
}
