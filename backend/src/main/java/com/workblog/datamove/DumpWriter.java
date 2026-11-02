package com.workblog.datamove;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workblog.datamove.DumpFormat.FileEntry;
import com.workblog.datamove.DumpFormat.Kind;
import com.workblog.datamove.DumpFormat.Manifest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.workblog.datamove.DumpFormat.instant;
import static com.workblog.datamove.DumpFormat.longOrNull;
import static com.workblog.datamove.DumpFormat.ts;

/**
 * 덤프 쓰기. 한 번의 읽기 전용(REPEATABLE READ) 트랜잭션 안에서 표를 행 단위로 읽어 zip 에 바로 쓴다(메모리에 모으지 않음).
 * since 가 있으면 업데이트 덤프: 그 시각 이후 이력·검토·생성이 있는 글과 그 이력·첨부, 이후 바뀐 댓글·공감만 담는다.
 * 사용자·카테고리·템플릿은 작아서 언제나 전부 담는다(대상에서 매핑을 찾을 수 있게).
 */
final class DumpWriter {
    /** 글이 since 이후 바뀌었나: 조회수 저장 때도 updated_at 이 바뀌므로 updated_at 대신 이력·검토·생성 시각으로 판단한다. */
    private static final String POST_CHANGED = " (p.created_at >= ? OR p.reviewed_at >= ? " +
            " OR EXISTS (SELECT 1 FROM post_revisions cr WHERE cr.post_id = p.id AND cr.created_at >= ?)) ";

    static final java.time.Duration OVERLAP = java.time.Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path storageRoot;
    private final ObjectMapper om = DumpFormat.mapper();

    DumpWriter(DataSource ds, PlatformTransactionManager tm, Path storageRoot) {
        this.jdbc = new JdbcTemplate(ds);
        this.jdbc.setFetchSize(200);
        this.tx = new TransactionTemplate(tm);
        this.tx.setReadOnly(true);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.storageRoot = storageRoot;
    }

    /** zip 을 out 에 쓰고 manifest 를 돌려준다. out 은 닫지 않는다. */
    Manifest write(OutputStream out, String instanceId, String appVersion, Instant since, Instant exportedAt) {
        return tx.execute(status -> {
            try {
                return doWrite(out, instanceId, appVersion, since, exportedAt);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** exportedAt 은 트랜잭션 시작 전에 잡은 시각(그 전에 커밋된 변경은 모두 이 덤프에 보인다). */
    private Manifest doWrite(OutputStream out, String instanceId, String appVersion, Instant since, Instant exportedAt) throws IOException {
        Kind kind = since == null ? Kind.FULL : Kind.UPDATE;
        String name = DumpFormat.dumpName(instanceId, exportedAt, kind);
        Map<String, Long> counts = new LinkedHashMap<>();
        List<FileEntry> files = new ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(new NonClosing(out), 64 * 1024));

        counts.put("users", table(zip, files, DumpFormat.USERS,
                "SELECT id, username, password, display_name, email, department, role, active, created_at, updated_at FROM users ORDER BY id",
                List.of(), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeStringField("username", rs.getString("username"));
                    g.writeStringField("password", rs.getString("password"));
                    g.writeStringField("displayName", rs.getString("display_name"));
                    g.writeStringField("email", rs.getString("email"));
                    g.writeStringField("department", rs.getString("department"));
                    g.writeStringField("role", rs.getString("role"));
                    g.writeBooleanField("active", rs.getBoolean("active"));
                    time(g, "createdAt", instant(rs, "created_at"));
                    time(g, "updatedAt", instant(rs, "updated_at"));
                }));

        Map<Long, List<String>> catPaths = new HashMap<>();
        counts.put("categories", table(zip, files, DumpFormat.CATEGORIES,
                "SELECT id, name, parent_id, depth, sort_order, restricted_departments, created_at FROM categories ORDER BY depth, sort_order, id",
                List.of(), (g, rs) -> {
                    long id = rs.getLong("id");
                    Long parent = longOrNull(rs, "parent_id");
                    List<String> path = new ArrayList<>(parent == null ? List.of() : catPaths.getOrDefault(parent, List.of()));
                    path.add(rs.getString("name"));
                    catPaths.put(id, path);
                    g.writeNumberField("id", id);
                    g.writeStringField("name", rs.getString("name"));
                    nullableLong(g, "parentId", parent);
                    g.writeArrayFieldStart("path");
                    for (String s : path) g.writeString(s);
                    g.writeEndArray();
                    g.writeNumberField("sortOrder", rs.getInt("sort_order"));
                    g.writeStringField("restrictedDepartments", rs.getString("restricted_departments"));
                    time(g, "createdAt", instant(rs, "created_at"));
                }));

        counts.put("templates", table(zip, files, DumpFormat.TEMPLATES,
                "SELECT id, name, description, content, category_id, tags, sort_order, created_at, updated_at FROM post_templates ORDER BY id",
                List.of(), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeStringField("name", rs.getString("name"));
                    g.writeStringField("description", rs.getString("description"));
                    g.writeStringField("content", rs.getString("content"));
                    nullableLong(g, "categoryId", longOrNull(rs, "category_id"));
                    g.writeStringField("tags", rs.getString("tags"));
                    g.writeNumberField("sortOrder", rs.getInt("sort_order"));
                    time(g, "createdAt", instant(rs, "created_at"));
                    time(g, "updatedAt", instant(rs, "updated_at"));
                }));

        // 기준 시각보다 조금 앞에서부터 담는다(기준 시각 직전에 시작해 늦게 커밋된 변경을 놓치지 않게). 겹쳐 담아도 가져오기는 멱등.
        Instant from = since == null ? null : since.minus(OVERLAP);
        List<Object> sinceArgs3 = since == null ? List.of() : List.of(ts(from), ts(from), ts(from));
        counts.put("posts", table(zip, files, DumpFormat.POSTS,
                "SELECT p.id, p.title, p.content, p.content_format, p.summary, p.category_id, p.tags, p.status, p.visibility, p.pinned, " +
                " p.author_id, p.version, p.view_count, p.created_at, p.updated_at, p.published_at, p.deleted_at, p.reviewed_at FROM posts p" +
                (since == null ? "" : " WHERE " + POST_CHANGED) + " ORDER BY p.id",
                sinceArgs3, (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeStringField("title", rs.getString("title"));
                    g.writeStringField("content", rs.getString("content"));
                    g.writeStringField("contentFormat", rs.getString("content_format"));
                    g.writeStringField("summary", rs.getString("summary"));
                    g.writeNumberField("categoryId", rs.getLong("category_id"));
                    g.writeStringField("tags", rs.getString("tags"));
                    g.writeStringField("status", rs.getString("status"));
                    g.writeStringField("visibility", rs.getString("visibility"));
                    g.writeBooleanField("pinned", rs.getBoolean("pinned"));
                    g.writeNumberField("authorId", rs.getLong("author_id"));
                    g.writeNumberField("version", rs.getInt("version"));
                    g.writeNumberField("viewCount", rs.getLong("view_count"));
                    time(g, "createdAt", instant(rs, "created_at"));
                    time(g, "updatedAt", instant(rs, "updated_at"));
                    time(g, "publishedAt", instant(rs, "published_at"));
                    time(g, "deletedAt", instant(rs, "deleted_at"));
                    time(g, "reviewedAt", instant(rs, "reviewed_at"));
                }));

        counts.put("revisions", table(zip, files, DumpFormat.REVISIONS,
                "SELECT id, post_id, version, title, content, content_format, summary, category, category_id, tags, status, visibility, " +
                " change_type, change_note, edited_by, created_at FROM post_revisions" + (since == null ? "" : " WHERE created_at >= ?") + " ORDER BY post_id, version",
                since == null ? List.of() : List.of(ts(from)), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeNumberField("postId", rs.getLong("post_id"));
                    g.writeNumberField("version", rs.getInt("version"));
                    g.writeStringField("title", rs.getString("title"));
                    g.writeStringField("content", rs.getString("content"));
                    g.writeStringField("contentFormat", rs.getString("content_format"));
                    g.writeStringField("summary", rs.getString("summary"));
                    g.writeStringField("category", rs.getString("category"));
                    nullableLong(g, "categoryId", longOrNull(rs, "category_id"));
                    g.writeStringField("tags", rs.getString("tags"));
                    g.writeStringField("status", rs.getString("status"));
                    g.writeStringField("visibility", rs.getString("visibility"));
                    g.writeStringField("changeType", rs.getString("change_type"));
                    g.writeStringField("changeNote", rs.getString("change_note"));
                    g.writeNumberField("editedBy", rs.getLong("edited_by"));
                    time(g, "createdAt", instant(rs, "created_at"));
                }));

        counts.put("comments", table(zip, files, DumpFormat.COMMENTS,
                "SELECT id, post_id, parent_id, author_id, content, deleted_at, created_at, updated_at FROM comments" +
                (since == null ? "" : " WHERE updated_at >= ?") + " ORDER BY id",
                since == null ? List.of() : List.of(ts(from)), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeNumberField("postId", rs.getLong("post_id"));
                    nullableLong(g, "parentId", longOrNull(rs, "parent_id"));
                    g.writeNumberField("authorId", rs.getLong("author_id"));
                    g.writeStringField("content", rs.getString("content"));
                    time(g, "deletedAt", instant(rs, "deleted_at"));
                    time(g, "createdAt", instant(rs, "created_at"));
                    time(g, "updatedAt", instant(rs, "updated_at"));
                }));

        counts.put("likes", table(zip, files, DumpFormat.LIKES,
                "SELECT post_id, user_id, created_at FROM post_likes" + (since == null ? "" : " WHERE created_at >= ?") + " ORDER BY post_id, user_id",
                since == null ? List.of() : List.of(ts(from)), (g, rs) -> {
                    g.writeNumberField("postId", rs.getLong("post_id"));
                    g.writeNumberField("userId", rs.getLong("user_id"));
                    time(g, "createdAt", instant(rs, "created_at"));
                }));

        // 사내 이전은 모든 값을 그대로 옮긴다: API 토큰(해시만 저장돼 있음)·알림·열람 감사 로그도 담는다.
        counts.put("apiTokens", table(zip, files, DumpFormat.API_TOKENS,
                "SELECT id, user_id, name, token_hash, token_prefix, created_at, expires_at, last_used_at, revoked_at FROM api_tokens ORDER BY id",
                List.of(), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeNumberField("userId", rs.getLong("user_id"));
                    g.writeStringField("name", rs.getString("name"));
                    g.writeStringField("tokenHash", rs.getString("token_hash"));
                    g.writeStringField("tokenPrefix", rs.getString("token_prefix"));
                    time(g, "createdAt", instant(rs, "created_at"));
                    time(g, "expiresAt", instant(rs, "expires_at"));
                    time(g, "lastUsedAt", instant(rs, "last_used_at"));
                    time(g, "revokedAt", instant(rs, "revoked_at"));
                }));

        counts.put("notifications", table(zip, files, DumpFormat.NOTIFICATIONS,
                "SELECT id, recipient_id, actor_id, type, post_id, comment_id, read_at, created_at FROM notifications" +
                (since == null ? "" : " WHERE created_at >= ? OR read_at >= ?") + " ORDER BY id",
                since == null ? List.of() : List.of(ts(from), ts(from)), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeNumberField("recipientId", rs.getLong("recipient_id"));
                    nullableLong(g, "actorId", longOrNull(rs, "actor_id"));
                    g.writeStringField("type", rs.getString("type"));
                    g.writeNumberField("postId", rs.getLong("post_id"));
                    nullableLong(g, "commentId", longOrNull(rs, "comment_id"));
                    time(g, "readAt", instant(rs, "read_at"));
                    time(g, "createdAt", instant(rs, "created_at"));
                }));

        counts.put("postViews", table(zip, files, DumpFormat.POST_VIEWS,
                "SELECT id, post_id, user_id, viewed_at, via FROM post_views" + (since == null ? "" : " WHERE viewed_at >= ?") + " ORDER BY id",
                since == null ? List.of() : List.of(ts(from)), (g, rs) -> {
                    g.writeNumberField("id", rs.getLong("id"));
                    g.writeNumberField("postId", rs.getLong("post_id"));
                    g.writeNumberField("userId", rs.getLong("user_id"));
                    time(g, "viewedAt", instant(rs, "viewed_at"));
                    g.writeStringField("via", rs.getString("via"));
                }));

        // 첨부: 전체 덤프는 모두, 업데이트 덤프는 바뀐 글의 첨부 + 이후 올라온 첨부. 파일을 먼저 쓰고 해시를 모은 뒤 메타 파일을 쓴다.
        String attSql = "SELECT a.id, a.public_id, a.post_id, a.uploader_id, a.original_name, a.stored_name, a.content_type, a.size_bytes, a.kind, a.created_at " +
                " FROM attachments a" +
                (since == null ? "" : " WHERE a.created_at >= ? OR EXISTS (SELECT 1 FROM posts p WHERE p.id = a.post_id AND " + POST_CHANGED + ")") + " ORDER BY a.id";
        List<Object> attArgs = since == null ? List.of() : List.of(ts(from), ts(from), ts(from), ts(from));
        Map<Long, String> shaById = new HashMap<>();
        long[] fileCount = {0};
        jdbc.query(attSql, rs -> {
            try {
                Path p = storageRoot.resolve(rs.getString("stored_name")).normalize();
                if (!p.startsWith(storageRoot) || !Files.isRegularFile(p)) return;   // 디스크에 없는 파일은 메타만(file=null)
                String entry = DumpFormat.FILES_DIR + rs.getString("public_id");
                Digesting d = begin(zip, entry);
                try (InputStream in = Files.newInputStream(p)) { in.transferTo(d); }
                files.add(end(zip, d, entry));
                shaById.put(rs.getLong("id"), files.get(files.size() - 1).sha256());
                fileCount[0]++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, attArgs.toArray());
        counts.put("files", fileCount[0]);
        counts.put("attachments", table(zip, files, DumpFormat.ATTACHMENTS, attSql, attArgs, (g, rs) -> {
            long id = rs.getLong("id");
            String sha = shaById.get(id);
            g.writeNumberField("id", id);
            g.writeStringField("publicId", rs.getString("public_id"));
            nullableLong(g, "postId", longOrNull(rs, "post_id"));
            g.writeNumberField("uploaderId", rs.getLong("uploader_id"));
            g.writeStringField("originalName", rs.getString("original_name"));
            g.writeStringField("contentType", rs.getString("content_type"));
            g.writeNumberField("sizeBytes", rs.getLong("size_bytes"));
            g.writeStringField("kind", rs.getString("kind"));
            time(g, "createdAt", instant(rs, "created_at"));
            g.writeStringField("sha256", sha);
            g.writeStringField("file", sha == null ? null : DumpFormat.FILES_DIR + rs.getString("public_id"));
        }));

        Manifest m = new Manifest(DumpFormat.FORMAT, DumpFormat.FORMAT_VERSION, appVersion, instanceId, name, kind, exportedAt, since, counts, files);
        zip.putNextEntry(new ZipEntry(DumpFormat.MANIFEST));
        zip.write(om.writerWithDefaultPrettyPrinter().writeValueAsBytes(m));
        zip.closeEntry();
        zip.finish();
        zip.flush();
        return m;
    }

    @FunctionalInterface
    private interface RowWriter { void write(JsonGenerator g, ResultSet rs) throws IOException, SQLException; }

    /** 표 하나를 JSON 배열 항목으로 쓰고 행 수를 돌려준다. */
    private long table(ZipOutputStream zip, List<FileEntry> files, String entry, String sql, List<Object> args, RowWriter row) throws IOException {
        Digesting d = begin(zip, entry);
        JsonGenerator g = om.getFactory().createGenerator(d);
        g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        g.writeStartArray();
        long[] n = {0};
        jdbc.query(sql, rs -> {
            try {
                g.writeStartObject();
                row.write(g, rs);
                g.writeEndObject();
                n[0]++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, args.toArray());
        g.writeEndArray();
        g.close();
        files.add(end(zip, d, entry));
        return n[0];
    }

    private static void time(JsonGenerator g, String f, Instant v) throws IOException {
        if (v == null) g.writeNullField(f); else g.writeStringField(f, v.toString());
    }

    private static void nullableLong(JsonGenerator g, String f, Long v) throws IOException {
        if (v == null) g.writeNullField(f); else g.writeNumberField(f, v);
    }

    private static Digesting begin(ZipOutputStream zip, String entry) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        return new Digesting(zip);
    }

    private static FileEntry end(ZipOutputStream zip, Digesting d, String entry) throws IOException {
        d.flush();
        zip.closeEntry();
        return new FileEntry(entry, HexFormat.of().formatHex(d.md.digest()), d.count);
    }

    /** 항목에 쓰는 바이트의 SHA-256·크기를 센다. close 는 zip 을 닫지 않는다. */
    private static final class Digesting extends FilterOutputStream {
        final MessageDigest md;
        long count;

        Digesting(OutputStream out) {
            super(out);
            try { md = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }

        @Override public void write(int b) throws IOException { out.write(b); md.update((byte) b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); md.update(b, off, len); count += len; }
        @Override public void close() throws IOException { flush(); }
    }

    /** 응답 스트림은 호출한 쪽이 닫는다. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) { super(out); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { flush(); }
    }
}
