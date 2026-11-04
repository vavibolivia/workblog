package com.workblog.ops;

import com.workblog.category.CategoryAccess;
import com.workblog.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 태그·부서 관리(관리자). 둘 다 따로 표가 없고 글자로 복사돼 있다:
 * 태그 = posts.tags · post_templates.tags (쉼표), 부서 = users.department · categories.restricted_departments (쉼표).
 * 그래서 이름 바꾸기·합치기·지우기는 복사본 모두를 한 트랜잭션에서 바꾼다(한쪽만 바뀌어 어긋나지 않게).
 * 비교는 대소문자·앞뒤 공백을 무시한다(태그 필터·열람 제한과 같은 기준).
 */
@Tag(name = "관리 — 태그·부서")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Transactional
public class TaxonomyAdminController {
    public record TagUsage(String name, long posts, long templates) {}
    public record DeptUsage(String name, long users, long categories) {}
    /** to 가 비어 있으면 지우기(태그만). 대소문자만 다른 것도 from 으로 함께 잡힌다. */
    public record RenameRequest(@NotBlank @Size(max = 100) String from, @Size(max = 100) String to) {}
    public record RenameResult(int posts, int templates, int users, int categories) {}

    private final JdbcTemplate jdbc;

    public TaxonomyAdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // ---------- 태그 ----------

    @GetMapping("/tags")
    @Transactional(readOnly = true)
    @Operation(summary = "태그 목록", description = "글자 그대로(대소문자 구분) 태그마다 쓰는 글 수(휴지통 포함)·템플릿 수")
    public List<TagUsage> tags() {
        Map<String, long[]> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        jdbc.query("SELECT tags FROM posts WHERE tags <> ''", rs -> { for (String t : split(rs.getString(1))) m.computeIfAbsent(t, k -> new long[2])[0]++; });
        jdbc.query("SELECT tags FROM post_templates WHERE tags <> ''", rs -> { for (String t : split(rs.getString(1))) m.computeIfAbsent(t, k -> new long[2])[1]++; });
        List<TagUsage> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new TagUsage(k, v[0], v[1])));
        return out;
    }

    @PutMapping("/tags/rename")
    @Operation(summary = "태그 이름 바꾸기·합치기·지우기", description = "from 과 같은 태그(대소문자 무시)를 모든 글·템플릿에서 to 로. to 가 이미 있으면 합쳐짐, 비우면 지움")
    public RenameResult renameTag(@Valid @RequestBody RenameRequest req) {
        String from = clean(req.from()), to = req.to() == null ? "" : clean(req.to());
        if (from.isEmpty()) throw ApiException.badRequest("바꿀 태그를 적으세요.");
        int p = renameIn("posts", from, to), t = renameIn("post_templates", from, to);
        return new RenameResult(p, t, 0, 0);
    }

    private int renameIn(String table, String from, String to) {
        int n = 0;
        List<Object[]> rows = jdbc.query("SELECT id, tags FROM " + table + " WHERE tags <> '' AND strpos(lower(tags), lower(?)) > 0",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)}, from);
        for (Object[] r : rows) {
            List<String> cur = split((String) r[1]);
            if (cur.stream().noneMatch(x -> key(x).equals(key(from)))) continue;
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            for (String x : cur) {
                String v = key(x).equals(key(from)) ? to : x;
                if (!v.isEmpty()) out.putIfAbsent(key(v), v);
            }
            String joined = String.join(",", out.values());
            if (joined.length() > 500) throw ApiException.badRequest("태그가 너무 길어지는 글이 있습니다(id " + r[0] + ").");
            n += jdbc.update("UPDATE " + table + " SET tags = ? WHERE id = ?", joined, r[0]);
        }
        return n;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static String clean(String s) {
        return s.trim().replace(",", " ").replaceAll("\\s+", " ");
    }

    // ---------- 부서 ----------

    @GetMapping("/departments")
    @Transactional(readOnly = true)
    @Operation(summary = "부서 목록", description = "사용자 부서와 카테고리 열람 제한에 쓰인 부서(대소문자 무시로 묶음)마다 사용자 수·카테고리 수")
    public List<DeptUsage> departments() {
        Map<String, Object[]> m = new TreeMap<>();
        jdbc.query("SELECT TRIM(department) FROM users WHERE COALESCE(TRIM(department), '') <> ''", rs -> {
            String d = rs.getString(1); Object[] e = m.computeIfAbsent(key(d), k -> new Object[]{d, 0L, 0L}); e[1] = (Long) e[1] + 1; });
        jdbc.query("SELECT restricted_departments FROM categories WHERE restricted_departments <> ''", rs -> {
            for (String d : CategoryAccess.parse(rs.getString(1))) { Object[] e = m.computeIfAbsent(key(d), k -> new Object[]{d, 0L, 0L}); e[2] = (Long) e[2] + 1; } });
        List<DeptUsage> out = new ArrayList<>();
        for (Object[] e : m.values()) out.add(new DeptUsage((String) e[0], (Long) e[1], (Long) e[2]));
        return out;
    }

    @PutMapping("/departments/rename")
    @Operation(summary = "부서 이름 바꾸기·합치기", description = "사용자 부서와 카테고리 열람 제한 목록을 함께 바꾼다(한쪽만 바뀌면 제한 카테고리가 안 보이거나 엉뚱하게 보임). to 가 이미 있으면 합쳐짐")
    public RenameResult renameDepartment(@Valid @RequestBody RenameRequest req) {
        String from = req.from().trim(), to = req.to() == null ? "" : req.to().trim();
        if (to.isEmpty()) throw ApiException.badRequest("새 부서 이름을 적으세요.");
        if (to.contains(",")) throw ApiException.badRequest("부서 이름에는 쉼표를 쓸 수 없습니다.");
        int users = jdbc.update("UPDATE users SET department = ? WHERE lower(trim(department)) = lower(?)", to, from);
        int cats = 0;
        List<Object[]> rows = jdbc.query("SELECT id, restricted_departments FROM categories WHERE restricted_departments <> ''",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)});
        for (Object[] r : rows) {
            List<String> cur = CategoryAccess.parse((String) r[1]);
            if (cur.stream().noneMatch(x -> key(x).equals(key(from)))) continue;
            List<String> next = new ArrayList<>();
            for (String x : cur) next.add(key(x).equals(key(from)) ? to : x);
            cats += jdbc.update("UPDATE categories SET restricted_departments = ? WHERE id = ?", CategoryAccess.join(next), r[0]);
        }
        // 공수 표 인원 행의 소속도 계정 부서를 따른다
        jdbc.update("UPDATE mm_rows SET department = ? WHERE lower(trim(department)) = lower(?) AND user_id IS NOT NULL", to, from);
        return new RenameResult(0, 0, users, cats);
    }
}
