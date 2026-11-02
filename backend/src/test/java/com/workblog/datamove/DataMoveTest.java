package com.workblog.datamove;

import com.workblog.attachment.AttachmentTextService;
import com.workblog.common.ApiException;
import com.workblog.config.WorkblogProperties;
import com.workblog.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.workblog.datamove.DumpFormat.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 두 인스턴스(H2 PostgreSQL 모드 DB 2개 + 저장소 폴더 2개)로 내보내기 → 가져오기를 끝까지 돌린다.
 * 운영 SQL 은 PostgreSQL 이지만 데이터 이전 코드는 이식 가능한 SQL 만 쓰므로 같은 코드를 그대로 검증한다.
 */
class DataMoveTest {
    @TempDir Path tmp;

    record Node(JdbcTemplate jdbc, DataMoveService svc, Path storage, long adminId) {}

    Node a, b;
    String pubA = UUID.randomUUID().toString();
    byte[] png = HexFormat.of().parseHex("89504e470d0a1a0a0000000d4948445200000001000000010806000000");
    long postA;

    @BeforeEach
    void setUp() throws Exception {
        a = node("a");
        b = node("b");
        JdbcTemplate j = a.jdbc();
        long u1 = id(j, "INSERT INTO users (username, password, display_name, department, role) VALUES ('kim', '$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234', '김정비', '정비', 'USER')");
        long root = id(j, "INSERT INTO categories (name, depth, sort_order) VALUES ('정비', 1, 1)");
        j.update("UPDATE categories SET path = ? WHERE id = ?", "/" + root + "/", root);
        long leaf = id(j, "INSERT INTO categories (name, parent_id, depth, sort_order, restricted_departments) VALUES ('엔진', " + root + ", 2, 1, '정비')");
        j.update("UPDATE categories SET path = ? WHERE id = ?", "/" + root + "/" + leaf + "/", leaf);
        postA = id(j, "INSERT INTO posts (title, content, content_format, category_id, tags, status, author_id, version) VALUES ('엔진 점검', " +
                "'<p>사진 <img src=\"/api/files/" + pubA + "\"></p>', 'HTML', " + leaf + ", '엔진', 'PUBLISHED', " + u1 + ", 1)");
        j.update("INSERT INTO post_revisions (post_id, version, title, content, content_format, category, category_id, tags, status, change_type, edited_by) " +
                "SELECT id, 1, title, content, 'HTML', '정비 > 엔진', category_id, tags, status, 'CREATE', author_id FROM posts WHERE id = ?", postA);
        Path f = a.storage().resolve("2026/10/" + pubA + ".png");
        Files.createDirectories(f.getParent());
        Files.write(f, png);
        j.update("INSERT INTO attachments (public_id, post_id, uploader_id, original_name, stored_name, content_type, size_bytes, kind) VALUES (?,?,?,?,?,?,?,?)",
                pubA, postA, u1, "그림.png", "2026/10/" + pubA + ".png", "image/png", png.length, "IMAGE");
        long c1 = id(j, "INSERT INTO comments (post_id, author_id, content) VALUES (" + postA + ", " + a.adminId() + ", '확인했습니다')");
        j.update("INSERT INTO comments (post_id, parent_id, author_id, content) VALUES (?,?,?,?)", postA, c1, u1, "답글");
        j.update("INSERT INTO post_likes (post_id, user_id) VALUES (?,?)", postA, a.adminId());
        j.update("INSERT INTO post_templates (name, content, tags) VALUES ('점검표', '<p>틀</p>', '')");
        j.update("INSERT INTO api_tokens (user_id, name, token_hash, token_prefix) VALUES (?,?,?,?)", u1, "cli", "a".repeat(64), "wbt_aaaa");
        j.update("INSERT INTO notifications (recipient_id, actor_id, type, post_id, comment_id) VALUES (?,?,?,?,?)", u1, a.adminId(), "COMMENT", postA, c1);
        j.update("INSERT INTO post_views (post_id, user_id, via) VALUES (?,?,?)", postA, a.adminId(), "WEB");
    }

    @Test
    void fullDumpImportsEverythingIntoAnotherInstance() throws Exception {
        ImportReport r = importInto(b, export(a, null), true);
        assertThat(r.status).isEqualTo("OK");
        JdbcTemplate j = b.jdbc();
        assertThat(j.queryForObject("SELECT password FROM users WHERE username = 'kim'", String.class)).startsWith("$2a$10$abc");
        assertThat(j.queryForObject("SELECT path FROM categories WHERE name = '엔진'", String.class)).matches("/\\d+/\\d+/");
        assertThat(j.queryForObject("SELECT restricted_departments FROM categories WHERE name = '엔진'", String.class)).isEqualTo("정비");
        Map<String, Object> post = j.queryForMap("SELECT id, content, version FROM posts WHERE title = '엔진 점검'");
        String newPub = j.queryForObject("SELECT public_id FROM attachments WHERE post_id = ?", String.class, post.get("id"));
        assertThat(newPub).isNotEqualTo(pubA);
        assertThat((String) post.get("content")).contains("/api/files/" + newPub).doesNotContain(pubA);
        String stored = j.queryForObject("SELECT stored_name FROM attachments WHERE public_id = ?", String.class, newPub);
        assertThat(Files.readAllBytes(b.storage().resolve(stored))).isEqualTo(png);
        assertThat(j.queryForObject("SELECT count(*) FROM comments WHERE parent_id IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(count(j, "post_revisions")).isEqualTo(1);
        assertThat(count(j, "post_likes")).isEqualTo(1);
        assertThat(count(j, "api_tokens")).isEqualTo(1);
        assertThat(count(j, "notifications")).isEqualTo(1);
        assertThat(count(j, "post_views")).isEqualTo(1);
        assertThat(count(j, "post_templates")).isEqualTo(1);
        // admin 은 대상에 이미 있으므로 새로 만들지 않고 대상 값 유지
        assertThat(j.queryForObject("SELECT password FROM users WHERE username = 'admin'", String.class)).isEqualTo("hash-b");
        assertThat(j.queryForObject("SELECT count(*) FROM data_transfers WHERE action = 'APPLY' AND status = 'OK'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameDumpTwiceIsIdempotent() throws Exception {
        Path dump = export(a, null);
        importInto(b, dump, true);
        Map<String, Integer> before = counts(b.jdbc());
        ImportReport second = importInto(b, dump, true);
        assertThat(counts(b.jdbc())).isEqualTo(before);
        for (ImportReport.Counts c : second.counts.values()) {
            assertThat(c.created).isZero();
            assertThat(c.updated).isZero();
        }
    }

    @Test
    void previewChangesNothing() throws Exception {
        Map<String, Integer> before = counts(b.jdbc());
        ImportReport p = importInto(b, export(a, null), false);
        assertThat(p.counts.get("posts").created).isEqualTo(1);
        assertThat(p.counts.get("attachments").created).isEqualTo(1);
        assertThat(counts(b.jdbc())).isEqualTo(before);
        assertThat(b.jdbc().queryForObject("SELECT count(*) FROM data_import_map", Integer.class)).isZero();
    }

    @Test
    void updateDumpUpdatesPostAsNewVersionWithNote() throws Exception {
        importInto(b, export(a, null), true);
        Instant since = Instant.now();
        Thread.sleep(5);
        editOnA("엔진 점검 (개정)");
        ImportReport r = importInto(b, export(a, since), true);
        assertThat(r.counts.get("posts").updated).isEqualTo(1);
        Map<String, Object> post = b.jdbc().queryForMap("SELECT id, title, version, content FROM posts WHERE title LIKE '엔진 점검%'");
        assertThat(post.get("title")).isEqualTo("엔진 점검 (개정)");
        assertThat(post.get("version")).isEqualTo(2);
        String note = b.jdbc().queryForObject("SELECT change_note FROM post_revisions WHERE post_id = ? AND version = 2", String.class, post.get("id"));
        assertThat(note).startsWith(DumpImporter.NOTE_PREFIX + "workblog-dump-");
        // 같은 첨부는 다시 만들지 않고 본문 주소도 그대로 대상 id
        assertThat(b.jdbc().queryForObject("SELECT count(*) FROM attachments", Integer.class)).isEqualTo(1);
        assertThat((String) post.get("content")).doesNotContain(pubA);
    }

    @Test
    void postEditedOnTargetIsReportedAsConflictAndKept() throws Exception {
        importInto(b, export(a, null), true);
        b.jdbc().update("UPDATE posts SET title = '방산망에서 고침', version = version + 1 WHERE title = '엔진 점검'");
        Instant since = Instant.now();
        Thread.sleep(5);
        editOnA("원본에서 고침");
        ImportReport r = importInto(b, export(a, since), true);
        assertThat(r.counts.get("posts").conflict).isEqualTo(1);
        assertThat(r.items).anyMatch(i -> i.action() == ImportReport.Action.CONFLICT && "posts".equals(i.entity()));
        assertThat(b.jdbc().queryForObject("SELECT title FROM posts", String.class)).isEqualTo("방산망에서 고침");
    }

    @Test
    void apiTokenOfPreexistingAccountIsNotImported() throws Exception {
        // 원본 admin 의 토큰을 대상의 (원래 있던) admin 에 붙이면 원본 토큰으로 대상 관리자로 들어올 수 있다
        a.jdbc().update("INSERT INTO api_tokens (user_id, name, token_hash, token_prefix) VALUES (?,?,?,?)", a.adminId(), "admin-cli", "b".repeat(64), "wbt_bbbb");
        importInto(b, export(a, null), true);
        assertThat(b.jdbc().queryForObject("SELECT count(*) FROM api_tokens WHERE token_hash = ?", Integer.class, "b".repeat(64))).isZero();
        assertThat(b.jdbc().queryForObject("SELECT count(*) FROM api_tokens WHERE token_hash = ?", Integer.class, "a".repeat(64))).isEqualTo(1);
    }

    @Test
    void commentEditedOnSourceIsUpdated() throws Exception {
        importInto(b, export(a, null), true);
        Instant since = Instant.now();
        Thread.sleep(5);
        a.jdbc().update("UPDATE comments SET content = '원본에서 고친 댓글', updated_at = ? WHERE content = '확인했습니다'", ts(Instant.now()));
        ImportReport r = importInto(b, export(a, since), true);
        assertThat(r.counts.get("comments").updated).isEqualTo(1);
        assertThat(b.jdbc().queryForObject("SELECT count(*) FROM comments WHERE content = '원본에서 고친 댓글'", Integer.class)).isEqualTo(1);
    }

    @Test
    void commentEditedOnTargetIsReportedAsConflictAndKept() throws Exception {
        importInto(b, export(a, null), true);
        Thread.sleep(5);
        b.jdbc().update("UPDATE comments SET content = '대상에서 고친 댓글', updated_at = ? WHERE content = '확인했습니다'", ts(Instant.now()));
        Instant since = Instant.now();
        Thread.sleep(5);
        a.jdbc().update("UPDATE comments SET content = '원본에서 고친 댓글', updated_at = ? WHERE content = '확인했습니다'", ts(Instant.now()));
        ImportReport r = importInto(b, export(a, since), true);
        assertThat(r.counts.get("comments").conflict).isEqualTo(1);
        assertThat(b.jdbc().queryForObject("SELECT count(*) FROM comments WHERE content = '대상에서 고친 댓글'", Integer.class)).isEqualTo(1);
    }

    @Test
    void zipSlipEntryIsRejected() throws Exception {
        Path z = tmp.resolve("slip.zip");
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(z))) {
            zo.putNextEntry(new ZipEntry("manifest.json"));
            zo.write("{}".getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("files/../../evil.sh"));
            zo.write("x".getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
        }
        assertThatThrownBy(() -> DumpArchive.open(z, 1L << 30)).isInstanceOf(ApiException.class).hasMessageContaining("경로 탈출");
    }

    @Test
    void zipBombEntryIsRejected() throws Exception {
        Path z = tmp.resolve("bomb.zip");
        byte[] zeros = new byte[20 * 1024 * 1024];
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(zeros));
        String manifest = "{\"format\":\"workblog-dump\",\"formatVersion\":1,\"sourceInstanceId\":\"" + UUID.randomUUID() + "\",\"dumpName\":\"x\",\"kind\":\"FULL\"," +
                "\"exportedAt\":\"2026-10-27T00:00:00Z\",\"counts\":{},\"files\":[{\"path\":\"files/bomb\",\"sha256\":\"" + sha + "\",\"size\":" + zeros.length + "}]}";
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(z))) {
            zo.putNextEntry(new ZipEntry("manifest.json"));
            zo.write(manifest.getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("files/bomb"));
            zo.write(zeros);
            zo.closeEntry();
        }
        assertThatThrownBy(() -> DumpArchive.open(z, 1L << 30)).isInstanceOf(ApiException.class).hasMessageContaining("과대 압축");
        assertThatThrownBy(() -> DumpArchive.open(z, 1024)).isInstanceOf(ApiException.class).hasMessageContaining("한도");
    }

    @Test
    void ownDumpIsRejected() throws Exception {
        byte[] bytes = Files.readAllBytes(export(a, null));
        assertThatThrownBy(() -> a.svc().upload(new ByteArrayInputStream(bytes), bytes.length, actor()))
                .isInstanceOf(ApiException.class).hasMessageContaining("자기 덤프");
    }

    // ---------- 도구 ----------

    private void editOnA(String title) {
        JdbcTemplate j = a.jdbc();
        j.update("UPDATE posts SET title = ?, version = 2 WHERE id = ?", title, postA);
        j.update("INSERT INTO post_revisions (post_id, version, title, content, content_format, category, category_id, tags, status, change_type, change_note, edited_by, created_at) " +
                "SELECT id, 2, title, content, 'HTML', '정비 > 엔진', category_id, tags, status, 'UPDATE', '제목 수정', author_id, ? FROM posts WHERE id = ?", ts(Instant.now()), postA);
    }

    private Path export(Node n, Instant since) throws Exception {
        DataMoveService.PreparedExport p = n.svc().prepareExport(since);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        n.svc().export(p, true, n.adminId(), "admin", out);
        Path z = tmp.resolve(UUID.randomUUID() + ".zip");
        Files.write(z, out.toByteArray());
        return z;
    }

    private ImportReport importInto(Node n, Path zip, boolean apply) throws Exception {
        byte[] bytes = Files.readAllBytes(zip);
        DataMoveDtos.UploadResult up = n.svc().upload(new ByteArrayInputStream(bytes), bytes.length, actor());
        if (!apply) { n.svc().discard(up.uploadId()); return up.preview(); }
        return n.svc().apply(up.uploadId(), actor());
    }

    private static User actor() {
        return new User("admin", "x", "관리자", null, null, User.Role.ADMIN);
    }

    private Node node(String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:dm-" + name + "-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("datamove/schema-h2.sql"));
        }
        JdbcTemplate j = new JdbcTemplate(ds);
        long admin = id(j, "INSERT INTO users (username, password, display_name, role) VALUES ('admin', 'hash-" + name + "', '관리자', 'ADMIN')");
        Path storage = tmp.resolve("files-" + name);
        Files.createDirectories(storage);
        WorkblogProperties props = new WorkblogProperties(null, null, null, new WorkblogProperties.Storage(storage.toString(), 25), null, null);
        DataMoveService svc = new DataMoveService(j, ds, new DataSourceTransactionManager(ds), new InstanceIdService(j),
                new AttachmentTextService(j, props), props, 2048, tmp.resolve("imports-" + name).toString());
        return new Node(j, svc, storage.toAbsolutePath().normalize(), admin);
    }

    private static long id(JdbcTemplate j, String insert) {
        org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        j.update(c -> c.prepareStatement(insert, new String[]{"id"}), kh);
        return kh.getKey().longValue();
    }

    private static int count(JdbcTemplate j, String table) {
        return j.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static Map<String, Integer> counts(JdbcTemplate j) {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (String t : List.of("users", "categories", "posts", "post_revisions", "attachments", "comments", "post_likes", "post_templates",
                "api_tokens", "notifications", "post_views")) m.put(t, count(j, t));
        return m;
    }
}
