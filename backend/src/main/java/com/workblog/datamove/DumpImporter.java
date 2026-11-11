package com.workblog.datamove;

import com.fasterxml.jackson.databind.JsonNode;
import com.workblog.datamove.ImportReport.Action;
import com.workblog.post.HtmlSanitizer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.workblog.datamove.DumpFormat.bool;
import static com.workblog.datamove.DumpFormat.cut;
import static com.workblog.datamove.DumpFormat.inst;
import static com.workblog.datamove.DumpFormat.instant;
import static com.workblog.datamove.DumpFormat.integer;
import static com.workblog.datamove.DumpFormat.lng;
import static com.workblog.datamove.DumpFormat.str;
import static com.workblog.datamove.DumpFormat.ts;

/**
 * 덤프 가져오기(병합). apply=false 면 미리 보기: 같은 판단을 하되 아무것도 쓰지 않는다.
 * <p>병합 규칙</p>
 * <ul>
 *   <li>매핑(data_import_map: 원본 인스턴스·종류·원본 키 → 대상 id)이 있으면 같은 대상을 갱신한다. 같은 덤프를 두 번 넣어도 결과가 같다.</li>
 *   <li>사용자: 아이디(대소문자 무시)가 있으면 대상 값을 그대로 두고 매핑만. 없으면 bcrypt 해시 그대로 만든다.</li>
 *   <li>카테고리: 전체 경로(이름, 대소문자 무시)로 맞추고 없으면 만든다.</li>
 *   <li>글: 원본 버전이 더 새로울 때만 갱신. 대상에서 그 사이 고친 글(현재 버전 ≠ 지난번 반영 버전)은 덮어쓰지 않고 '충돌'.
 *       갱신은 새 버전(이력)으로 남기고 change note 에 "데이터 가져오기: 덤프 이름".</li>
 *   <li>첨부: 매핑 또는 같은 글의 같은 SHA-256 파일이 있으면 다시 만들지 않는다. 본문 /api/files/원본id 는 대상 id 로 바꾼다.</li>
 *   <li>이력·댓글·공감·알림·열람 기록·API 토큰: 매핑이나 자연 키로 중복 없이.</li>
 * </ul>
 * 단계별·묶음별로 커밋한다(한 트랜잭션이 너무 커지지 않게). 실패하면 그 단계와 마지막으로 커밋된 원본 id 를 보고서에 남긴다.
 */
final class DumpImporter {
    interface TextHook { void extract(long attachmentId, String storedName, String originalName); }

    static final String NOTE_PREFIX = "데이터 가져오기: ";
    private static final Pattern FILE_REF = Pattern.compile("/api/files/([0-9a-fA-F-]{36})");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final int POST_CHUNK = 50;
    private static final int ROW_CHUNK = 500;

    private enum PA { CREATE, UPDATE, SKIP, CONFLICT }

    /** 원본 글 하나의 이번 실행 결과. offset = 대상 버전 - 원본 버전. */
    private record PostState(long targetId, int offset, int maxSrcVersion, int prevSrcVersion, PA action) {}

    private record MapRow(long targetId, String targetKey, Integer srcVer, Integer tgtVer, Instant srcUpd, Instant tgtUpd) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path storageRoot;
    private final DumpArchive dump;
    private final boolean apply;
    /** 설치·주기 병합: 원본에서 바뀐 것은 이 서버에서도 고쳤더라도 원본 것으로 갱신한다(이 서버 것은 이력에 남는다). 관리 화면 적용은 false(충돌로 보고). */
    private final boolean sourceWins;
    private final TextHook textHook;
    private final String src;
    private final String note;
    private final ImportReport rep = new ImportReport();

    /** USER 매핑의 target_key: 가져오기가 만든 계정(원래 있던 계정과 구분). */
    private static final String CREATED = "created";
    /** TEMPLATE 매핑의 target_key: 이 서버에 원래 있던 같은 이름의 템플릿에 맞춘 것(갱신하지 않는다). */
    private static final String KEPT = "kept";

    private final Map<Long, Long> users = new HashMap<>();
    private final Set<Long> createdUsers = new java.util.HashSet<>();
    private final Map<Long, Long> cats = new HashMap<>();
    /** 열람 제한 충돌로 가져오지 않은 원본 카테고리(하위 포함). */
    private final Set<Long> blockedCats = new java.util.HashSet<>();
    private final Map<Long, Integer> catDepth = new HashMap<>();
    private final Map<Long, Long> comments = new HashMap<>();
    private final Map<Long, PostState> posts = new HashMap<>();
    private final Map<String, String> filePub = new HashMap<>();
    private Map<String, MapRow> postMap = Map.of();
    private Map<String, MapRow> commentMap = Map.of();
    private Map<Long, List<JsonNode>> attByPost = Map.of();
    private final List<JsonNode> orphanAtts = new ArrayList<>();
    private final List<Path> chunkFiles = new ArrayList<>();
    private final List<Object[]> chunkExtract = new ArrayList<>();
    private long fakeSeq = 0;
    private String current;

    DumpImporter(JdbcTemplate jdbc, TransactionTemplate tx, Path storageRoot, DumpArchive dump, boolean apply, TextHook textHook) {
        this(jdbc, tx, storageRoot, dump, apply, false, textHook);
    }

    DumpImporter(JdbcTemplate jdbc, TransactionTemplate tx, Path storageRoot, DumpArchive dump, boolean apply, boolean sourceWins, TextHook textHook) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storageRoot = storageRoot;
        this.dump = dump;
        this.apply = apply;
        this.sourceWins = sourceWins;
        this.textHook = textHook;
        DumpFormat.Manifest m = dump.manifest();
        this.src = m.sourceInstanceId();
        this.note = NOTE_PREFIX + m.dumpName();
        rep.dumpName = m.dumpName();
        rep.sourceInstanceId = m.sourceInstanceId();
        rep.kind = m.kind().name();
        rep.exportedAt = m.exportedAt();
        rep.since = m.since();
        rep.applied = apply;
    }

    ImportReport run() {
        String stage = null;
        try {
            stage = "users"; users(); rep.completedStages.add(stage);
            stage = "categories"; categories(); rep.completedStages.add(stage);
            stage = "templates"; templates(); rep.completedStages.add(stage);
            stage = "posts"; loadAttachments(); posts(); rep.completedStages.add(stage);
            stage = "revisions"; revisions(); ensureRevisions(); rep.completedStages.add(stage);
            stage = "attachments"; orphanAttachments(); rep.completedStages.add(stage);
            stage = "comments"; comments(); rep.completedStages.add(stage);
            stage = "likes"; likes(); rep.completedStages.add(stage);
            stage = "notifications"; notifications(); rep.completedStages.add(stage);
            stage = "postViews"; postViews(); rep.completedStages.add(stage);
            stage = "apiTokens"; apiTokens(); rep.completedStages.add(stage);
        } catch (RuntimeException e) {
            rep.status = "FAILED";
            rep.failedStage = stage;
            rep.error = cut(String.valueOf(e.getMessage()), 1000);
            if (current != null) rep.error = rep.error + " (처리 중: " + stage + " #" + current + ")";
        }
        return rep;
    }

    // ---------- 사용자 ----------

    private void users() {
        Map<String, MapRow> m = loadMap("USER");
        chunked(DumpFormat.USERS, ROW_CHUNK, "users", n -> {
            long sid = lng(n, "id");
            String uname = str(n, "username");
            if (uname == null || uname.isBlank()) { rep.add("users", Action.FAIL, sid, null, null, "아이디가 없습니다", true); return; }
            MapRow row = m.get(key(sid));
            Long tid = mapped(row, "users");
            if (tid != null && CREATED.equals(row.targetKey())) createdUsers.add(tid);
            if (tid == null) tid = first(jdbc.queryForList("SELECT id FROM users WHERE lower(username) = lower(?)", Long.class, uname));
            if (tid != null) {
                users.put(sid, tid);
                Instant sUpd = inst(n, "updatedAt");
                if (sUpd != null) sUpd = sUpd.truncatedTo(ChronoUnit.MICROS);
                boolean changed = false;
                // 같은 계정: 같은 아이디 + 같은 생성 시각(가져오기로 만든 계정·통째 복제본). 아이디만 같은 이 서버 고유 계정은 건드리지 않는다.
                Instant sCreated = inst(n, "createdAt");
                Instant tCreated = tid > 0 ? jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?", (rs, i) -> instant(rs, "created_at"), tid) : null;
                boolean sameAccount = createdUsers.contains(tid) || (sCreated != null && tCreated != null
                        && sCreated.truncatedTo(ChronoUnit.MICROS).equals(tCreated.truncatedTo(ChronoUnit.MICROS)));
                if (sourceWins && sUpd != null && tid > 0 && sameAccount) {
                    // 병합(설치·주기): 원본에서 바뀐 계정은 원본 값으로(이름·메일·부서·직급·권한·사용 여부·비밀번호). 이 서버의 admin 비밀번호는 그대로.
                    Instant base = row != null && row.srcUpd() != null ? row.srcUpd()
                            : jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", (rs, i) -> instant(rs, "updated_at"), tid);
                    if (base == null || sUpd.isAfter(base.truncatedTo(ChronoUnit.MICROS))) {
                        String role = "ADMIN".equals(str(n, "role")) ? "ADMIN" : "USER";
                        String hash = str(n, "password");
                        boolean keepPw = "admin".equalsIgnoreCase(uname) || hash == null || hash.isEmpty();
                        String display = str(n, "displayName");
                        if (apply) {
                            jdbc.update("UPDATE users SET display_name = ?, email = ?, department = ?, position = ?, role = ?, active = ?, " +
                                            (keepPw ? "" : "password = ?, ") + "updated_at = ? WHERE id = ?",
                                    keepPw
                                            ? new Object[]{cut(display == null || display.isBlank() ? uname : display, 100), cut(str(n, "email"), 200), cut(str(n, "department"), 100),
                                                    cut(str(n, "position"), 100), role, bool(n, "active"), ts(now()), tid}
                                            : new Object[]{cut(display == null || display.isBlank() ? uname : display, 100), cut(str(n, "email"), 200), cut(str(n, "department"), 100),
                                                    cut(str(n, "position"), 100), role, bool(n, "active"), cut(hash, 100), ts(now()), tid});
                        }
                        changed = true;
                    }
                }
                saveMap("USER", key(sid), new MapRow(tid, createdUsers.contains(tid) ? CREATED : null, null, null, sUpd, null));
                if (changed) rep.add("users", Action.UPDATE, sid, tid, uname, "원본 값으로 갱신", true);
                else rep.add("users", Action.SKIP, sid, tid, uname, !sourceWins ? "같은 아이디가 있어 이 서버의 값을 유지"
                        : sameAccount ? "변경 없음" : "같은 아이디의 이 서버 고유 계정 — 이 서버 값 유지", true);
                return;
            }
            String role = "ADMIN".equals(str(n, "role")) ? "ADMIN" : "USER";
            String hash = str(n, "password");
            if (hash == null || hash.isEmpty()) hash = "!no-password";
            String display = str(n, "displayName");
            Instant created = orNow(inst(n, "createdAt"));
            if (apply) {
                tid = insert("INSERT INTO users (username, password, display_name, email, department, position, role, active, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        cut(uname.trim(), 50), cut(hash, 100), cut(display == null || display.isBlank() ? uname : display, 100), cut(str(n, "email"), 200),
                        cut(str(n, "department"), 100), cut(str(n, "position"), 100), role, bool(n, "active"), ts(created), ts(orNow(inst(n, "updatedAt"))));
            } else {
                tid = fake();
            }
            users.put(sid, tid);
            createdUsers.add(tid);
            saveMap("USER", key(sid), new MapRow(tid, CREATED, null, null, inst(n, "updatedAt"), null));
            rep.add("users", Action.CREATE, sid, tid, uname + (display == null ? "" : " (" + display + ")"), null, true);
        });
    }

    // ---------- 카테고리 ----------

    private void categories() {
        Map<String, MapRow> m = loadMap("CATEGORY");
        chunked(DumpFormat.CATEGORIES, ROW_CHUNK, "categories", n -> {
            long sid = lng(n, "id");
            String name = cut(String.valueOf(str(n, "name")).trim(), 50);
            Long sp = lng(n, "parentId");
            List<String> pathNames = new ArrayList<>();
            if (n.get("path") != null) n.get("path").forEach(x -> pathNames.add(x.asText()));
            String label = pathNames.isEmpty() ? name : String.join(" > ", pathNames);
            Long tp = null;
            if (sp != null) {
                tp = cats.get(sp);
                if (tp == null) {
                    if (blockedCats.contains(sp)) { blockedCats.add(sid); rep.add("categories", Action.SKIP, sid, null, label, "상위 카테고리가 열람 제한 충돌로 빠짐", true); }
                    else rep.add("categories", Action.FAIL, sid, null, label, "상위 카테고리를 찾지 못했습니다", true);
                    return;
                }
            }
            Long tid = mapped(m.get(key(sid)), "categories");
            if (tid == null) tid = findChild(tp, name);
            if (tid != null && looserThanSource(tid, str(n, "restrictedDepartments"))) {
                // 원본에서 일부 부서만 보던 글이 여기서 더 넓게 보이게 된다 → 이 카테고리(와 그 글·하위)는 가져오지 않는다
                blockedCats.add(sid);
                rep.add("categories", Action.CONFLICT, sid, tid, label,
                        "원본은 열람 부서 제한(" + nz(str(n, "restrictedDepartments")) + ")이 있는데 이 서버의 같은 카테고리는 더 넓게 열려 있음 — 이 서버의 제한을 맞춘 뒤 다시 가져오세요", true);
                return;
            }
            if (tid != null) {
                cats.put(sid, tid);
                saveMap("CATEGORY", key(sid), new MapRow(tid, null, null, null, null, null));
                rep.add("categories", Action.SKIP, sid, tid, label, "같은 경로가 있어 이 서버의 값을 유지", true);
                return;
            }
            int depth = tp == null ? 1 : depthOf(tp) + 1;
            if (depth > 5) { rep.add("categories", Action.FAIL, sid, null, label, "5단계를 넘습니다", true); return; }
            if (apply) {
                Instant now = now();
                tid = insert("INSERT INTO categories (name, parent_id, depth, path, sort_order, restricted_departments, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                        name, tp, depth, "", integer(n, "sortOrder", 0), cut(nz(com.workblog.category.CategoryAccess.join(com.workblog.category.CategoryAccess.parse(str(n, "restrictedDepartments")))), 1000),
                        ts(orNow(inst(n, "createdAt"))), ts(now));
                String parentPath = tp == null ? "/" : jdbc.queryForObject("SELECT path FROM categories WHERE id = ?", String.class, tp);
                jdbc.update("UPDATE categories SET path = ? WHERE id = ?", parentPath + tid + "/", tid);
            } else {
                tid = fake();
            }
            catDepth.put(tid, depth);
            cats.put(sid, tid);
            saveMap("CATEGORY", key(sid), new MapRow(tid, null, null, null, null, null));
            rep.add("categories", Action.CREATE, sid, tid, label, null, true);
        });
    }

    private Long findChild(Long parent, String name) {
        if (parent != null && parent < 0) return null;
        List<Long> ids = parent == null
                ? jdbc.queryForList("SELECT id FROM categories WHERE parent_id IS NULL AND lower(name) = lower(?)", Long.class, name)
                : jdbc.queryForList("SELECT id FROM categories WHERE parent_id = ? AND lower(name) = lower(?)", Long.class, parent, name);
        return first(ids);
    }

    /** 이 서버 카테고리가 원본의 열람 부서 제한보다 넓게 열려 있는가(제한 없음 포함). 원본에 제한이 없으면 false. */
    private boolean looserThanSource(long catId, String srcRestricted) {
        List<String> src = com.workblog.category.CategoryAccess.parse(srcRestricted);
        if (src.isEmpty()) return false;
        List<String> tgt = com.workblog.category.CategoryAccess.parse(
                first(jdbc.queryForList("SELECT restricted_departments FROM categories WHERE id = ?", String.class, catId)));
        return tgt.isEmpty() || !src.containsAll(tgt);
    }

    private int depthOf(long catId) {
        Integer d = catDepth.get(catId);
        if (d != null) return d;
        d = jdbc.queryForObject("SELECT depth FROM categories WHERE id = ?", Integer.class, catId);
        catDepth.put(catId, d);
        return d;
    }

    // ---------- 템플릿 ----------

    private void templates() {
        Map<String, MapRow> m = loadMap("TEMPLATE");
        chunked(DumpFormat.TEMPLATES, ROW_CHUNK, "templates", n -> {
            long sid = lng(n, "id");
            String name = cut(nz(str(n, "name")), 100);
            Instant srcUpd = inst(n, "updatedAt");
            Long tcat = lng(n, "categoryId") == null ? null : cats.get(lng(n, "categoryId"));
            if (tcat != null && tcat < 0) tcat = null;
            MapRow row = m.get(key(sid));
            Long tid = mapped(row, "post_templates");
            if (tid != null && KEPT.equals(row.targetKey())) {
                rep.add("templates", Action.SKIP, sid, tid, name, "같은 이름의 템플릿이 있어 이 서버의 값을 유지", false);
                return;
            }
            if (tid != null) {
                if (row.srcUpd() != null && srcUpd != null && !srcUpd.isAfter(row.srcUpd())) {
                    rep.add("templates", Action.SKIP, sid, tid, name, "변경 없음", true);
                    return;
                }
                Instant tUpd = jdbc.queryForObject("SELECT updated_at FROM post_templates WHERE id = ?", (rs, i) -> instant(rs, "updated_at"), tid);
                if (!sourceWins && row.tgtUpd() != null && tUpd != null && !tUpd.equals(row.tgtUpd())) {
                    rep.add("templates", Action.CONFLICT, sid, tid, name, "이 서버에서 지난 가져오기 뒤에 고친 템플릿 — 덮어쓰지 않음", true);
                    return;
                }
                Instant now = now();
                if (apply) {
                    jdbc.update("UPDATE post_templates SET name = ?, description = ?, content = ?, category_id = ?, tags = ?, sort_order = ?, updated_at = ? WHERE id = ?",
                            name, cut(str(n, "description"), 300), HtmlSanitizer.clean(nz(str(n, "content"))), tcat, cut(nz(str(n, "tags")), 500),
                            integer(n, "sortOrder", 0), ts(now), tid);
                }
                saveMap("TEMPLATE", key(sid), new MapRow(tid, null, null, null, srcUpd, now));
                rep.add("templates", Action.UPDATE, sid, tid, name, null, true);
                return;
            }
            tid = first(jdbc.queryForList("SELECT id FROM post_templates WHERE lower(name) = lower(?)", Long.class, name));
            if (tid != null) {
                Instant tUpd = jdbc.queryForObject("SELECT updated_at FROM post_templates WHERE id = ?", (rs, i) -> instant(rs, "updated_at"), tid);
                saveMap("TEMPLATE", key(sid), new MapRow(tid, KEPT, null, null, srcUpd, tUpd));
                rep.add("templates", Action.SKIP, sid, tid, name, "같은 이름의 템플릿이 있어 이 서버의 값을 유지", true);
                return;
            }
            Instant now = now();
            if (apply) {
                tid = insert("INSERT INTO post_templates (name, description, content, category_id, tags, sort_order, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                        name, cut(str(n, "description"), 300), HtmlSanitizer.clean(nz(str(n, "content"))), tcat, cut(nz(str(n, "tags")), 500),
                        integer(n, "sortOrder", 0), ts(orNow(inst(n, "createdAt"))), ts(now));
            } else {
                tid = fake();
            }
            saveMap("TEMPLATE", key(sid), new MapRow(tid, null, null, null, srcUpd, now));
            rep.add("templates", Action.CREATE, sid, tid, name, null, true);
        });
    }

    // ---------- 글 + 그 글의 첨부 ----------

    private void loadAttachments() {
        Map<String, MapRow> m = loadMap("ATTACHMENT");
        for (Map.Entry<String, MapRow> e : m.entrySet()) {
            if (e.getValue().targetKey() != null) filePub.put(e.getKey(), e.getValue().targetKey());
        }
        Map<Long, List<JsonNode>> by = new HashMap<>();
        dump.forEach(DumpFormat.ATTACHMENTS, n -> {
            Long pid = lng(n, "postId");
            if (pid == null) orphanAtts.add(n); else by.computeIfAbsent(pid, k -> new ArrayList<>()).add(n);
        });
        attByPost = by;
    }

    private void posts() {
        postMap = loadMap("POST");
        chunked(DumpFormat.POSTS, POST_CHUNK, "posts", this::post);
    }

    private void post(JsonNode n) {
        long sid = lng(n, "id");
        int sver = integer(n, "version", 1);
        String title = cut(nz(str(n, "title")), 300);
        Long scat = lng(n, "categoryId");
        Long tcat = scat == null ? null : cats.get(scat);
        Long tauthor = users.get(lng(n, "authorId"));
        if (tcat == null || tauthor == null) {
            rep.add("posts", Action.FAIL, sid, null, title, tcat == null ? (blockedCats.contains(scat) ? "카테고리 열람 제한 충돌로 가져오지 않음(카테고리 항목 참고)" : "카테고리를 찾지 못했습니다") : "작성자를 찾지 못했습니다", true);
            return;
        }
        MapRow r = postMap.get(key(sid));
        String format = "MARKDOWN".equals(str(n, "contentFormat")) ? "MARKDOWN" : "HTML";
        String status = "DRAFT".equals(str(n, "status")) ? "DRAFT" : "PUBLISHED";
        String vis = "PRIVATE".equals(str(n, "visibility")) ? "PRIVATE" : "PUBLIC";
        if (r != null) {
            List<Integer> cur = jdbc.queryForList("SELECT version FROM posts WHERE id = ?", Integer.class, r.targetId());
            if (cur.isEmpty()) { rep.add("posts", Action.SKIP, sid, r.targetId(), title, "이 서버에서 영구 삭제된 글 — 다시 만들지 않음", true); return; }
            int offset = r.tgtVer() - r.srcVer();
            if (sver <= r.srcVer()) {
                posts.put(sid, new PostState(r.targetId(), offset, r.srcVer(), r.srcVer(), PA.SKIP));
                rep.add("posts", Action.SKIP, sid, r.targetId(), title, "변경 없음", false);
                return;
            }
            int curVer = cur.get(0);
            String why = null;
            if (curVer != r.tgtVer()) {
                if (!sourceWins) {
                    posts.put(sid, new PostState(r.targetId(), offset, r.srcVer(), r.srcVer(), PA.CONFLICT));
                    rep.add("posts", Action.CONFLICT, sid, r.targetId(), title,
                            "이 서버에서 지난 가져오기(v" + r.tgtVer() + ") 뒤에 고친 글(현재 v" + curVer + ") — 원본 v" + sver + " 로 덮어쓰지 않음", true);
                    return;
                }
                // 원본이 바뀌었으면 원본 것으로. 이 서버에서 고친 v(지난 가져오기+1)~v현재 는 이력에 그대로 남고, 원본의 새 버전은 그 뒤에 붙는다.
                offset = curVer - r.srcVer();
                why = "이 서버 수정본 v" + curVer + " 은 이력에 남김";
            }
            updatePost(n, sid, r.targetId(), curVer, offset, r.srcVer(), title, format, tcat, status, vis, tauthor, why);
            return;
        }
        Long same = sameOrigin(n, tauthor);
        if (same != null) {
            // 매핑은 없지만 같은 글(같은 작성자·같은 작성 시각): 예전에 DB 를 통째로 넣어 설치했거나 매핑 기록이 없는 서버
            int tver = jdbc.queryForObject("SELECT version FROM posts WHERE id = ?", Integer.class, same);
            Instant tUpd = jdbc.queryForObject("SELECT updated_at FROM posts WHERE id = ?", (rs, i) -> instant(rs, "updated_at"), same);
            if (tUpd != null) tUpd = tUpd.truncatedTo(ChronoUnit.MICROS);
            Instant sUpd = inst(n, "updatedAt");
            if (sUpd != null) sUpd = sUpd.truncatedTo(ChronoUnit.MICROS);
            boolean sourceNewer = sUpd != null && (tUpd == null || sUpd.isAfter(tUpd));
            if (!sourceNewer) {
                posts.put(sid, new PostState(same, tver - sver, sver, sver, PA.SKIP));
                saveMap("POST", key(sid), new MapRow(same, null, sver, tver, inst(n, "updatedAt"), null));
                rep.add("posts", Action.SKIP, sid, same, title, sUpd != null && sUpd.equals(tUpd) ? "같은 글 — 변경 없음" : "같은 글 — 이 서버 것이 더 최근이라 유지", false);
                return;
            }
            int newVer = Math.max(sver, tver + 1);
            int offset = newVer - sver;
            updatePost(n, sid, same, tver, offset, tver - offset, title, format, tcat, status, vis, tauthor, "같은 글 — 원본이 더 최근");
            return;
        }
        List<JsonNode> toCreate = planAttachments(sid, null);
        String content = body(nz(str(n, "content")), format);
        long tid;
        if (apply) {
            tid = insert("INSERT INTO posts (title, content, content_format, summary, category_id, tags, status, visibility, pinned, author_id, version, view_count, " +
                            " created_at, updated_at, published_at, deleted_at, reviewed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    title, content, format, cut(str(n, "summary"), 500), tcat, cut(nz(str(n, "tags")), 500), status, vis, bool(n, "pinned"), tauthor,
                    sver, lng(n, "viewCount") == null ? 0L : lng(n, "viewCount"), ts(orNow(inst(n, "createdAt"))), ts(orNow(inst(n, "updatedAt"))),
                    ts(inst(n, "publishedAt")), ts(inst(n, "deletedAt")), ts(inst(n, "reviewedAt")));
            for (JsonNode a : toCreate) createAttachment(a, tid, tauthor);
        } else {
            tid = fake();
        }
        posts.put(sid, new PostState(tid, 0, sver, 0, PA.CREATE));
        saveMap("POST", key(sid), new MapRow(tid, null, sver, sver, inst(n, "updatedAt"), null));
        rep.add("posts", Action.CREATE, sid, tid, title, inst(n, "deletedAt") != null ? "휴지통 상태로" : null, true);
    }

    /**
     * 원본 글로 대상 글을 갱신한다. expectVer = 지금 대상 버전(읽은 뒤 누가 저장했으면 덮어쓰지 않고 이 묶음을 실패시킨다),
     * 새 버전 = 원본 버전 + offset, prevSrc = 이 서버에 이미 반영된 원본 버전(그 뒤 이력에 가져오기 표시).
     */
    private void updatePost(JsonNode n, long sid, long tid, int expectVer, int offset, int prevSrc, String title, String format, Long tcat,
                            String status, String vis, long tauthor, String why) {
        int sver = integer(n, "version", 1);
        int newVer = sver + offset;
        List<JsonNode> toCreate = planAttachments(sid, tid);
        String content = body(nz(str(n, "content")), format);
        if (apply) {
            int n2 = jdbc.update("UPDATE posts SET title = ?, content = ?, content_format = ?, summary = ?, category_id = ?, tags = ?, status = ?, visibility = ?, pinned = ?, " +
                            " version = ?, updated_at = ?, published_at = ?, deleted_at = ?, reviewed_at = ? WHERE id = ? AND version = ?",
                    title, content, format, cut(str(n, "summary"), 500), tcat, cut(nz(str(n, "tags")), 500), status, vis, bool(n, "pinned"),
                    newVer, ts(now()), ts(inst(n, "publishedAt")), ts(inst(n, "deletedAt")), ts(inst(n, "reviewedAt")), tid, expectVer);
            if (n2 == 0) throw new IllegalStateException("가져오는 동안 이 서버에서 글이 고쳐졌습니다(#" + tid + "). 다시 가져오세요.");
            for (JsonNode a : toCreate) createAttachment(a, tid, tauthor);
        }
        posts.put(sid, new PostState(tid, offset, sver, prevSrc, PA.UPDATE));
        saveMap("POST", key(sid), new MapRow(tid, null, sver, newVer, inst(n, "updatedAt"), null));
        rep.add("posts", Action.UPDATE, sid, tid, title, "v" + expectVer + " → v" + newVer + (why == null ? "" : " (" + why + ")"), true);
    }

    /** 매핑 없는 원본 글과 같은 글: 같은 작성자 · 같은 작성 시각(마이크로초)이고 이 원본의 다른 글에 아직 매핑되지 않은 것. */
    private Long sameOrigin(JsonNode n, long tauthor) {
        Instant created = inst(n, "createdAt");
        if (created == null || tauthor <= 0) return null;
        return first(jdbc.queryForList("SELECT p.id FROM posts p WHERE p.author_id = ? AND p.created_at = ? AND NOT EXISTS " +
                        "(SELECT 1 FROM data_import_map m WHERE m.source_instance = ? AND m.entity = 'POST' AND m.target_id = p.id) ORDER BY p.id",
                Long.class, tauthor, ts(created.truncatedTo(ChronoUnit.MICROS)), src));
    }

    /** 이 글의 첨부 중 새로 만들 것을 고르고 대상 public id 를 정한다(본문 주소 치환용). */
    private List<JsonNode> planAttachments(long srcPostId, Long targetPostId) {
        List<JsonNode> create = new ArrayList<>();
        for (JsonNode a : attByPost.getOrDefault(srcPostId, List.of())) {
            String spub = str(a, "publicId");
            if (spub == null) continue;
            if (filePub.containsKey(spub)) {
                rep.add("attachments", Action.SKIP, spub, null, str(a, "originalName"), "이미 가져옴", false);
                continue;
            }
            String file = str(a, "file");
            if (!dump.has(file)) {
                rep.add("attachments", Action.FAIL, spub, null, str(a, "originalName"), "덤프에 파일이 없습니다(원본 서버에서도 없던 파일)", true);
                continue;
            }
            String sha = dump.entry(file).sha256();
            if (targetPostId != null) {
                String same = first(jdbc.queryForList("SELECT public_id FROM attachments WHERE post_id = ? AND content_sha256 = ?", String.class, targetPostId, sha));
                if (same != null) {
                    filePub.put(spub, same);
                    Long aid = first(jdbc.queryForList("SELECT id FROM attachments WHERE public_id = ?", Long.class, same));
                    if (aid != null) saveMap("ATTACHMENT", spub, new MapRow(aid, same, null, null, null, null));
                    rep.add("attachments", Action.SKIP, spub, aid, str(a, "originalName"), "같은 글에 같은 파일(SHA-256)이 있음", false);
                    continue;
                }
            }
            filePub.put(spub, UUID.randomUUID().toString());
            create.add(a);
            if (!apply) rep.add("attachments", Action.CREATE, spub, null, str(a, "originalName"), null, false);
        }
        return create;
    }

    private void orphanAttachments() {
        List<JsonNode> todo = new ArrayList<>();
        for (JsonNode a : orphanAtts) {
            String spub = str(a, "publicId");
            if (spub == null) continue;
            if (filePub.containsKey(spub)) { rep.add("attachments", Action.SKIP, spub, null, str(a, "originalName"), "이미 가져옴", false); continue; }
            if (!dump.has(str(a, "file"))) { rep.add("attachments", Action.FAIL, spub, null, str(a, "originalName"), "덤프에 파일이 없습니다", true); continue; }
            Long up = users.get(lng(a, "uploaderId"));
            if (up == null) { rep.add("attachments", Action.FAIL, spub, null, str(a, "originalName"), "올린 사람을 찾지 못했습니다", true); continue; }
            String sha = dump.entry(str(a, "file")).sha256();
            String samePub = up > 0 ? first(jdbc.queryForList("SELECT public_id FROM attachments WHERE post_id IS NULL AND uploader_id = ? AND content_sha256 = ?",
                    String.class, up, sha)) : null;
            if (samePub != null) {
                filePub.put(spub, samePub);
                Long aid = first(jdbc.queryForList("SELECT id FROM attachments WHERE public_id = ?", Long.class, samePub));
                if (aid != null) saveMap("ATTACHMENT", spub, new MapRow(aid, samePub, null, null, null, null));
                rep.add("attachments", Action.SKIP, spub, aid, str(a, "originalName"), "같은 파일(SHA-256)이 있음", false);
                continue;
            }
            filePub.put(spub, UUID.randomUUID().toString());
            if (apply) todo.add(a); else rep.add("attachments", Action.CREATE, spub, null, str(a, "originalName"), "글에 연결 안 된 업로드", false);
        }
        for (int i = 0; i < todo.size(); i += ROW_CHUNK) {
            List<JsonNode> part = todo.subList(i, Math.min(todo.size(), i + ROW_CHUNK));
            commitChunk("attachments", part, a -> createAttachment(a, null, users.get(lng(a, "uploaderId"))));
        }
    }

    private void createAttachment(JsonNode a, Long targetPostId, long uploaderFallback) {
        String spub = str(a, "publicId");
        current = spub;
        String tpub = filePub.get(spub);
        String original = safeName(str(a, "originalName"));
        String ext = extension(original);
        LocalDate today = LocalDate.now();
        String rel = today.getYear() + "/" + String.format("%02d", today.getMonthValue()) + "/" + tpub + (ext.isEmpty() ? "" : "." + ext);
        Path dst = storageRoot.resolve(rel).normalize();
        if (!dst.startsWith(storageRoot)) throw new IllegalStateException("저장 경로가 저장소 밖입니다");
        String file = str(a, "file");
        long size;
        try {
            Files.createDirectories(dst.getParent());
            chunkFiles.add(dst);
            try (OutputStream o = Files.newOutputStream(dst)) { size = dump.copy(file, o); }
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 파일을 저장하지 못했습니다: " + original, e);
        }
        String ct = nz(str(a, "contentType")).trim();
        try { MediaType.parseMediaType(ct); } catch (Exception e) { ct = "application/octet-stream"; }
        String low = ct.toLowerCase(Locale.ROOT);
        String kind = str(a, "kind");
        boolean risky = low.contains("html") || low.contains("svg") || low.contains("xml") || low.contains("javascript") || Set.of("html", "htm", "svg", "xhtml", "js").contains(ext);
        // 화면에 바로 여는(inline) 것은 사진만. 스크립트를 담을 수 있는 형식은 내려받기 전용(FILE)으로.
        if (!"IMAGE".equals(kind) && !"EXCEL".equals(kind)) kind = "FILE";
        if ("IMAGE".equals(kind) && (risky || !low.startsWith("image/") || !IMAGE_EXT.contains(ext))) kind = "FILE";
        Long up = users.get(lng(a, "uploaderId"));
        long id = insert("INSERT INTO attachments (public_id, post_id, uploader_id, original_name, stored_name, content_type, size_bytes, kind, created_at, content_sha256) VALUES (?,?,?,?,?,?,?,?,?,?)",
                tpub, targetPostId, up == null ? uploaderFallback : up, original, rel, cut(ct, 150), size, kind, ts(orNow(inst(a, "createdAt"))),
                dump.entry(file).sha256());
        saveMap("ATTACHMENT", spub, new MapRow(id, tpub, null, null, null, null));
        if (!"IMAGE".equals(kind)) chunkExtract.add(new Object[]{id, rel, original});
        rep.add("attachments", Action.CREATE, spub, id, original, null, false);
    }

    /** 본문 속 /api/files/원본id → 대상 id, HTML 은 정화. */
    private String body(String content, String format) {
        Matcher mm = FILE_REF.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (mm.find()) {
            String t = filePub.get(mm.group(1));
            mm.appendReplacement(sb, Matcher.quoteReplacement("/api/files/" + (t == null ? mm.group(1) : t)));
        }
        mm.appendTail(sb);
        return "HTML".equals(format) ? HtmlSanitizer.clean(sb.toString()) : sb.toString();
    }

    // ---------- 이력 ----------

    private PostState postState(Long srcPostId) {
        if (srcPostId == null) return null;
        PostState st = posts.get(srcPostId);
        if (st != null) return st;
        MapRow r = postMap.get(key(srcPostId));
        if (r == null || mapped(r, "posts") == null) return null;
        st = new PostState(r.targetId(), r.tgtVer() - r.srcVer(), r.srcVer(), r.srcVer(), PA.SKIP);
        posts.put(srcPostId, st);
        return st;
    }

    private void revisions() {
        Map<String, MapRow> m = loadMap("REVISION");
        chunked(DumpFormat.REVISIONS, ROW_CHUNK, "revisions", n -> {
            long sid = lng(n, "id");
            if (mapped(m.get(key(sid)), "post_revisions") != null) { rep.add("revisions", Action.SKIP, sid, null, null, "이미 가져옴", false); return; }
            PostState st = postState(lng(n, "postId"));
            int sver = integer(n, "version", 1);
            if (st == null || st.action() == PA.CONFLICT || sver > st.maxSrcVersion()) {
                rep.add("revisions", Action.SKIP, sid, null, null, st == null ? "글이 이 서버에 없음" : "글을 반영하지 않음", false);
                return;
            }
            int tver = sver + st.offset();
            if (tver < 1) { rep.add("revisions", Action.SKIP, sid, null, null, "버전 범위 밖", false); return; }
            if (st.targetId() > 0) {
                Long existing = first(jdbc.queryForList("SELECT id FROM post_revisions WHERE post_id = ? AND version = ?", Long.class, st.targetId(), tver));
                if (existing != null) {
                    saveMap("REVISION", key(sid), new MapRow(existing, null, sver, tver, null, null));
                    rep.add("revisions", Action.SKIP, sid, existing, null, "같은 버전이 있음", false);
                    return;
                }
            }
            Long editor = users.get(lng(n, "editedBy"));
            if (editor == null) { rep.add("revisions", Action.FAIL, sid, null, str(n, "title"), "편집자를 찾지 못했습니다", true); return; }
            boolean imported = st.action() == PA.UPDATE && sver > st.prevSrcVersion();
            String orig = str(n, "changeNote");
            String chNote = imported ? cut(note + (orig == null || orig.isBlank() ? "" : " · " + orig), 500) : cut(orig, 500);
            String format = "MARKDOWN".equals(str(n, "contentFormat")) ? "MARKDOWN" : "HTML";
            Long scat = lng(n, "categoryId");
            Long tcat = scat == null ? null : cats.get(scat);
            if (tcat != null && tcat < 0) tcat = null;
            String ct = str(n, "changeType");
            if (ct == null || !Set.of("CREATE", "UPDATE", "RESTORE", "DELETE", "UNDELETE").contains(ct)) ct = "UPDATE";
            long tid;
            if (apply) {
                tid = insert("INSERT INTO post_revisions (post_id, version, title, content, content_format, summary, category, category_id, tags, status, visibility, " +
                                " change_type, change_note, edited_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        st.targetId(), tver, cut(nz(str(n, "title")), 300), body(nz(str(n, "content")), format), format, cut(str(n, "summary"), 500),
                        cut(nz(str(n, "category")), 300), tcat, cut(nz(str(n, "tags")), 500), "DRAFT".equals(str(n, "status")) ? "DRAFT" : "PUBLISHED",
                        "PRIVATE".equals(str(n, "visibility")) ? "PRIVATE" : "PUBLIC", ct, chNote, editor, ts(orNow(inst(n, "createdAt"))));
            } else {
                tid = fake();
            }
            saveMap("REVISION", key(sid), new MapRow(tid, null, sver, tver, null, null));
            rep.add("revisions", Action.CREATE, sid, tid, null, null, false);
        });
    }

    /** 새로 만들거나 갱신한 글의 현재 버전에 이력이 없으면(덤프에 그 이력이 없었으면) 지금 상태로 한 건 남긴다. */
    private void ensureRevisions() {
        if (!apply) return;
        List<PostState> todo = new ArrayList<>();
        for (PostState st : posts.values()) if (st.action() == PA.CREATE || st.action() == PA.UPDATE) todo.add(st);
        for (int i = 0; i < todo.size(); i += ROW_CHUNK) {
            commitChunk("revisions", todo.subList(i, Math.min(todo.size(), i + ROW_CHUNK)), st -> {
                current = String.valueOf(st.targetId());
                Integer ver = jdbc.queryForObject("SELECT version FROM posts WHERE id = ?", Integer.class, st.targetId());
                Integer has = jdbc.queryForObject("SELECT count(*) FROM post_revisions WHERE post_id = ? AND version = ?", Integer.class, st.targetId(), ver);
                if (has != null && has > 0) return;
                Long catId = jdbc.queryForObject("SELECT category_id FROM posts WHERE id = ?", Long.class, st.targetId());
                jdbc.update("INSERT INTO post_revisions (post_id, version, title, content, content_format, summary, category, category_id, tags, status, visibility, " +
                                " change_type, change_note, edited_by, created_at) " +
                                " SELECT p.id, p.version, p.title, p.content, p.content_format, p.summary, ?, p.category_id, p.tags, p.status, p.visibility, ?, ?, p.author_id, ? " +
                                " FROM posts p WHERE p.id = ?",
                        cut(categoryPathText(catId), 300), st.action() == PA.CREATE ? "CREATE" : "UPDATE", cut(note, 500), ts(now()), st.targetId());
                rep.add("revisions", Action.CREATE, null, st.targetId(), null, "현재 상태 스냅숏", false);
            });
        }
    }

    private String categoryPathText(Long catId) {
        if (catId == null) return "";
        String path = first(jdbc.queryForList("SELECT path FROM categories WHERE id = ?", String.class, catId));
        if (path == null) return "";
        List<String> names = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty()) continue;
            String nm = first(jdbc.queryForList("SELECT name FROM categories WHERE id = ?", String.class, Long.parseLong(s)));
            if (nm != null) names.add(nm);
        }
        return String.join(" > ", names);
    }

    // ---------- 댓글 · 공감 · 알림 · 열람 기록 · API 토큰 ----------

    private void comments() {
        commentMap = loadMap("COMMENT");
        chunked(DumpFormat.COMMENTS, ROW_CHUNK, "comments", n -> {
            long sid = lng(n, "id");
            PostState st = postState(lng(n, "postId"));
            if (st == null) { rep.add("comments", Action.SKIP, sid, null, null, "글이 이 서버에 없음", false); return; }
            MapRow r = commentMap.get(key(sid));
            Long tid = mapped(r, "comments");
            Instant upd = inst(n, "updatedAt");
            if (upd != null) upd = upd.truncatedTo(ChronoUnit.MICROS);
            Long author0 = users.get(lng(n, "authorId"));
            if (tid == null && st.targetId() > 0 && author0 != null && author0 > 0 && inst(n, "createdAt") != null) {
                // 매핑 없는 같은 댓글(같은 글 · 작성자 · 작성 시각): 이 서버 값을 기준으로 맞춘다(원본이 더 최근이면 아래에서 갱신)
                Long same = first(jdbc.queryForList("SELECT c.id FROM comments c WHERE c.post_id = ? AND c.author_id = ? AND c.created_at = ? AND NOT EXISTS " +
                                "(SELECT 1 FROM data_import_map m WHERE m.source_instance = ? AND m.entity = 'COMMENT' AND m.target_id = c.id) ORDER BY c.id",
                        Long.class, st.targetId(), author0, ts(inst(n, "createdAt").truncatedTo(ChronoUnit.MICROS)), src));
                if (same != null) {
                    Instant tUpd = jdbc.queryForObject("SELECT updated_at FROM comments WHERE id = ?", (rs, i) -> instant(rs, "updated_at"), same);
                    if (tUpd != null) tUpd = tUpd.truncatedTo(ChronoUnit.MICROS);
                    tid = same;
                    r = new MapRow(same, null, null, null, tUpd, tUpd);
                }
            }
            if (tid != null) {
                comments.put(sid, tid);
                if (r.srcUpd() == null || upd == null || !upd.isAfter(r.srcUpd())) {
                    saveMap("COMMENT", key(sid), new MapRow(tid, null, null, null, r.srcUpd(), r.tgtUpd()));
                    rep.add("comments", Action.SKIP, sid, tid, null, "변경 없음", false);
                    return;
                }
                Instant tUpd = jdbc.queryForObject("SELECT updated_at FROM comments WHERE id = ?", (rs, i) -> instant(rs, "updated_at"), tid);
                if (!sourceWins && r.tgtUpd() != null && tUpd != null && !tUpd.equals(r.tgtUpd())) {
                    rep.add("comments", Action.CONFLICT, sid, tid, null, "이 서버에서 지난 가져오기 뒤에 고친 댓글 — 덮어쓰지 않음", true);
                    return;
                }
                if (apply) jdbc.update("UPDATE comments SET content = ?, deleted_at = ?, updated_at = ? WHERE id = ?",
                        nz(str(n, "content")), ts(inst(n, "deletedAt")), ts(upd), tid);
                saveMap("COMMENT", key(sid), new MapRow(tid, null, null, null, upd, upd));
                rep.add("comments", Action.UPDATE, sid, tid, null, null, false);
                return;
            }
            Long sparent = lng(n, "parentId");
            Long tparent = null;
            if (sparent != null) {
                tparent = comments.get(sparent);
                if (tparent == null) tparent = mapped(commentMap.get(key(sparent)), "comments");
                if (tparent == null) { rep.add("comments", Action.SKIP, sid, null, null, "상위 댓글이 이 서버에 없음", false); return; }
            }
            Long author = users.get(lng(n, "authorId"));
            if (author == null) { rep.add("comments", Action.FAIL, sid, null, null, "작성자를 찾지 못했습니다", true); return; }
            Instant tUpd = orNow(upd);
            if (apply) {
                tid = insert("INSERT INTO comments (post_id, parent_id, author_id, content, deleted_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                        st.targetId(), tparent, author, nz(str(n, "content")), ts(inst(n, "deletedAt")), ts(orNow(inst(n, "createdAt"))), ts(tUpd));
            } else {
                tid = fake();
            }
            comments.put(sid, tid);
            saveMap("COMMENT", key(sid), new MapRow(tid, null, null, null, upd, tUpd));
            rep.add("comments", Action.CREATE, sid, tid, null, null, false);
        });
    }

    private void likes() {
        chunked(DumpFormat.LIKES, ROW_CHUNK, "likes", n -> {
            PostState st = postState(lng(n, "postId"));
            Long u = users.get(lng(n, "userId"));
            String id = lng(n, "postId") + "/" + lng(n, "userId");
            if (st == null || u == null) { rep.add("likes", Action.SKIP, id, null, null, "글이나 사용자가 이 서버에 없음", false); return; }
            if (st.targetId() > 0 && u > 0) {
                Integer c = jdbc.queryForObject("SELECT count(*) FROM post_likes WHERE post_id = ? AND user_id = ?", Integer.class, st.targetId(), u);
                if (c != null && c > 0) { rep.add("likes", Action.SKIP, id, null, null, "이미 있음", false); return; }
            }
            if (apply) jdbc.update("INSERT INTO post_likes (post_id, user_id, created_at) VALUES (?,?,?)", st.targetId(), u, ts(orNow(inst(n, "createdAt"))));
            rep.add("likes", Action.CREATE, id, null, null, null, false);
        });
    }

    private void notifications() {
        Map<String, MapRow> m = loadMap("NOTIFICATION");
        chunked(DumpFormat.NOTIFICATIONS, ROW_CHUNK, "notifications", n -> {
            long sid = lng(n, "id");
            MapRow r = m.get(key(sid));
            Long tid = mapped(r, "notifications");
            Instant readAt = inst(n, "readAt");
            if (tid != null) {
                if (readAt != null && apply) jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ? AND read_at IS NULL", ts(readAt), tid);
                rep.add("notifications", Action.SKIP, sid, tid, null, "이미 가져옴", false);
                return;
            }
            PostState st = postState(lng(n, "postId"));
            Long rcpt = users.get(lng(n, "recipientId"));
            Long actor = lng(n, "actorId") == null ? null : users.get(lng(n, "actorId"));
            Long scomment = lng(n, "commentId");
            Long tcomment = scomment == null ? null : comments.getOrDefault(scomment, mapped(commentMap.get(key(scomment)), "comments"));
            if (st == null || rcpt == null || (scomment != null && tcomment == null) || (lng(n, "actorId") != null && actor == null)) {
                rep.add("notifications", Action.SKIP, sid, null, null, "관련 글·댓글·사용자가 이 서버에 없음", false);
                return;
            }
            Instant created = inst(n, "createdAt");
            if (created != null && st.targetId() > 0 && rcpt > 0) {
                Long same = first(jdbc.queryForList("SELECT id FROM notifications WHERE recipient_id = ? AND post_id = ? AND type = ? AND created_at = ?",
                        Long.class, rcpt, st.targetId(), cut(nz(str(n, "type")), 20), ts(created.truncatedTo(ChronoUnit.MICROS))));
                if (same != null) {
                    saveMap("NOTIFICATION", key(sid), new MapRow(same, null, null, null, null, null));
                    rep.add("notifications", Action.SKIP, sid, same, null, "같은 알림이 있음", false);
                    return;
                }
            }
            if (apply) {
                tid = insert("INSERT INTO notifications (recipient_id, actor_id, type, post_id, comment_id, read_at, created_at) VALUES (?,?,?,?,?,?,?)",
                        rcpt, actor, cut(nz(str(n, "type")), 20), st.targetId(), tcomment, ts(readAt), ts(orNow(inst(n, "createdAt"))));
            } else {
                tid = fake();
            }
            saveMap("NOTIFICATION", key(sid), new MapRow(tid, null, null, null, null, null));
            rep.add("notifications", Action.CREATE, sid, tid, null, null, false);
        });
    }

    private void postViews() {
        chunked(DumpFormat.POST_VIEWS, ROW_CHUNK, "postViews", n -> {
            long sid = lng(n, "id");
            PostState st = postState(lng(n, "postId"));
            Long u = users.get(lng(n, "userId"));
            Instant at = inst(n, "viewedAt");
            if (st == null || u == null || at == null) { rep.add("postViews", Action.SKIP, sid, null, null, "글이나 사용자가 이 서버에 없음", false); return; }
            if (st.targetId() > 0 && u > 0) {
                Integer c = jdbc.queryForObject("SELECT count(*) FROM post_views WHERE post_id = ? AND user_id = ? AND viewed_at = ?", Integer.class, st.targetId(), u, ts(at));
                if (c != null && c > 0) { rep.add("postViews", Action.SKIP, sid, null, null, "이미 있음", false); return; }
            }
            if (apply) jdbc.update("INSERT INTO post_views (post_id, user_id, viewed_at, via) VALUES (?,?,?,?)", st.targetId(), u, ts(at), cut(nz(str(n, "via")), 20));
            rep.add("postViews", Action.CREATE, sid, null, null, null, false);
        });
    }

    private void apiTokens() {
        chunked(DumpFormat.API_TOKENS, ROW_CHUNK, "apiTokens", n -> {
            long sid = lng(n, "id");
            String hash = str(n, "tokenHash");
            Long u = users.get(lng(n, "userId"));
            String label = str(n, "tokenPrefix") + " " + nz(str(n, "name"));
            if (hash == null || u == null) { rep.add("apiTokens", Action.SKIP, sid, null, label, "사용자가 이 서버에 없음", false); return; }
            // 원래 이 서버에 있던 계정에 다른 서버의 토큰을 붙이면 그 토큰으로 이 계정에 들어올 수 있다 → 가져오기로 만든 계정만.
            if (!createdUsers.contains(u)) { rep.add("apiTokens", Action.SKIP, sid, null, label, "이 서버에 원래 있던 계정 — 토큰은 이 서버에서 새로 발급", false); return; }
            Long existing = first(jdbc.queryForList("SELECT id FROM api_tokens WHERE token_hash = ?", Long.class, hash));
            if (existing != null) {
                // 원본에서 폐기한 토큰은 여기서도 폐기한다(되살리지는 않는다)
                Instant revoked = inst(n, "revokedAt");
                int changed = revoked == null ? 0 : apply
                        ? jdbc.update("UPDATE api_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", ts(revoked), existing)
                        : jdbc.queryForObject("SELECT count(*) FROM api_tokens WHERE id = ? AND revoked_at IS NULL", Integer.class, existing);
                if (changed > 0) rep.add("apiTokens", Action.UPDATE, sid, existing, label, "원본에서 폐기 → 이 서버에서도 폐기", true);
                else rep.add("apiTokens", Action.SKIP, sid, existing, label, "이미 있음", false);
                return;
            }
            if (apply) jdbc.update("INSERT INTO api_tokens (user_id, name, token_hash, token_prefix, created_at, expires_at, last_used_at, revoked_at) VALUES (?,?,?,?,?,?,?,?)",
                    u, cut(nz(str(n, "name")), 100), cut(hash, 64), cut(nz(str(n, "tokenPrefix")), 16), ts(orNow(inst(n, "createdAt"))),
                    ts(inst(n, "expiresAt")), ts(inst(n, "lastUsedAt")), ts(inst(n, "revokedAt")));
            rep.add("apiTokens", Action.CREATE, sid, null, label, null, false);
        });
    }

    // ---------- 묶음 커밋 ----------

    private void chunked(String path, int size, String stage, Consumer<JsonNode> each) {
        List<JsonNode> buf = new ArrayList<>();
        dump.forEach(path, n -> {
            buf.add(n);
            if (buf.size() >= size) { commitChunk(stage, buf, each); buf.clear(); }
        });
        if (!buf.isEmpty()) commitChunk(stage, buf, each);
    }

    /** 묶음 하나를 한 트랜잭션으로. 실패하면 이 묶음에서 쓴 파일을 지운다. 커밋 뒤 첨부 텍스트 추출. */
    private <T> void commitChunk(String stage, List<T> items, Consumer<T> each) {
        chunkFiles.clear();
        chunkExtract.clear();
        String[] last = {null};
        Runnable work = () -> {
            for (T it : items) {
                if (it instanceof JsonNode n && n.get("id") != null) current = n.get("id").asText();
                each.accept(it);
                last[0] = current;
            }
        };
        try {
            if (apply) tx.executeWithoutResult(s -> work.run()); else work.run();
        } catch (RuntimeException e) {
            for (Path p : chunkFiles) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
            throw e;
        }
        if (apply) rep.lastCommitted = stage + " #" + last[0];
        if (textHook != null) {
            for (Object[] x : chunkExtract) {
                try { textHook.extract((Long) x[0], (String) x[1], (String) x[2]); } catch (RuntimeException ignored) {}
            }
        }
        current = null;
    }

    // ---------- 매핑 ----------

    private Map<String, MapRow> loadMap(String entity) {
        Map<String, MapRow> m = new HashMap<>();
        jdbc.query("SELECT source_key, target_id, target_key, source_version, target_version, source_updated_at, target_updated_at FROM data_import_map " +
                " WHERE source_instance = ? AND entity = ?", rs -> {
            m.put(rs.getString("source_key"), new MapRow(rs.getLong("target_id"), rs.getString("target_key"), intOrNull(rs, "source_version"),
                    intOrNull(rs, "target_version"), instant(rs, "source_updated_at"), instant(rs, "target_updated_at")));
        }, src, entity);
        return m;
    }

    private void saveMap(String entity, String key, MapRow r) {
        if (!apply) return;
        Object[] vals = {r.targetId(), r.targetKey(), r.srcVer(), r.tgtVer(), ts(r.srcUpd()), ts(r.tgtUpd()), ts(now())};
        int n = jdbc.update("UPDATE data_import_map SET target_id = ?, target_key = ?, source_version = ?, target_version = ?, source_updated_at = ?, target_updated_at = ?, applied_at = ? " +
                " WHERE source_instance = ? AND entity = ? AND source_key = ?", vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], src, entity, key);
        if (n == 0) {
            jdbc.update("INSERT INTO data_import_map (target_id, target_key, source_version, target_version, source_updated_at, target_updated_at, applied_at, source_instance, entity, source_key) " +
                    " VALUES (?,?,?,?,?,?,?,?,?,?)", vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6], src, entity, key);
        }
    }

    /** 매핑 대상이 아직 있으면 그 id. 표 이름은 이 클래스의 상수만 들어온다. */
    private Long mapped(MapRow r, String table) {
        if (r == null || r.targetId() <= 0) return null;
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, r.targetId());
        return c != null && c > 0 ? r.targetId() : null;
    }

    // ---------- 작은 도구 ----------

    private long insert(String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            new ArgumentPreparedStatementSetter(args).setValues(ps);
            return ps;
        }, kh);
        Map<String, Object> keys = kh.getKeys();
        Object v = keys == null || keys.isEmpty() ? null : keys.size() == 1 ? keys.values().iterator().next() : keys.getOrDefault("id", keys.get("ID"));
        if (!(v instanceof Number num)) throw new IllegalStateException("새 id 를 받지 못했습니다");
        return num.longValue();
    }

    private long fake() { return --fakeSeq; }

    private static String key(long id) { return String.valueOf(id); }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private static Instant orNow(Instant i) { return i == null ? now() : i; }

    private static <T> T first(List<T> list) { return list.isEmpty() ? null : list.get(0); }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static String safeName(String name) {
        String n = (name == null ? "file" : name).replace("\\", "/");
        n = n.substring(n.lastIndexOf('/') + 1).trim().replaceAll("[\\p{Cntrl}]", "");
        if (n.isEmpty()) n = "file";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        String e = i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
        return e.matches("[a-z0-9]{1,10}") ? e : "";
    }
}
