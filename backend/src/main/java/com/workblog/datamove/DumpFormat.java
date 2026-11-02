package com.workblog.datamove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 데이터 이전 덤프(.zip) 형식.
 * <pre>
 * manifest.json            형식 버전 · 앱 버전 · 원본 인스턴스 ID · 내보낸 시각 · since · 항목별 건수 · 파일 SHA-256 목록
 * data/users.json          사용자(bcrypt 해시 포함)      data/categories.json  카테고리(경로·순서·열람 제한)
 * data/templates.json      템플릿                       data/posts.json       글(삭제된 글은 deletedAt)
 * data/revisions.json      글 이력                      data/comments.json    댓글
 * data/likes.json          공감                         data/attachments.json 첨부 메타
 * data/apitokens.json      API 토큰(SHA-256 해시)        data/notifications.json 알림
 * data/postviews.json      열람 감사 로그
 * files/&lt;원본 public id&gt;  첨부 파일 원본
 * </pre>
 * 엔티티 파일은 JSON 배열 하나(스트리밍으로 쓰고 읽는다).
 */
public final class DumpFormat {
    private DumpFormat() {}

    public static final String FORMAT = "workblog-dump";
    public static final int FORMAT_VERSION = 1;
    public static final String MANIFEST = "manifest.json";

    public static final String USERS = "data/users.json";
    public static final String CATEGORIES = "data/categories.json";
    public static final String TEMPLATES = "data/templates.json";
    public static final String POSTS = "data/posts.json";
    public static final String REVISIONS = "data/revisions.json";
    public static final String COMMENTS = "data/comments.json";
    public static final String LIKES = "data/likes.json";
    public static final String ATTACHMENTS = "data/attachments.json";
    public static final String API_TOKENS = "data/apitokens.json";
    public static final String NOTIFICATIONS = "data/notifications.json";
    public static final String POST_VIEWS = "data/postviews.json";
    public static final List<String> DATA_FILES = List.of(USERS, CATEGORIES, TEMPLATES, POSTS, REVISIONS, COMMENTS, LIKES, ATTACHMENTS,
            API_TOKENS, NOTIFICATIONS, POST_VIEWS);
    public static final String FILES_DIR = "files/";

    /** zip 항목 이름으로 허용하는 것(그 밖은 모두 거부: 경로 탈출 방지). */
    static final Pattern ENTRY_NAME = Pattern.compile("^(manifest\\.json|data/[a-z]+\\.json|files/[A-Za-z0-9-]{1,64})$");

    public enum Kind { FULL, UPDATE }

    public record FileEntry(String path, String sha256, long size) {}

    public record Manifest(String format, int formatVersion, String appVersion, String sourceInstanceId, String dumpName,
                           Kind kind, Instant exportedAt, Instant since, Map<String, Long> counts, List<FileEntry> files) {}

    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /** workblog-dump-&lt;인스턴스 앞 8자&gt;-&lt;yyyyMMdd-HHmm&gt;-full|-update.zip (시각은 서버 시간대) */
    public static String dumpName(String instanceId, Instant exportedAt, Kind kind) {
        String inst = instanceId.length() > 8 ? instanceId.substring(0, 8) : instanceId;
        return "workblog-dump-" + inst + "-" + NAME_TIME.format(exportedAt.atZone(ZoneId.systemDefault()))
                + (kind == Kind.FULL ? "-full" : "-update") + ".zip";
    }

    public static ObjectMapper mapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    }

    // ---------- JDBC · JSON 값 변환 ----------

    static SqlParameterValue ts(Instant i) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC));
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static String str(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Long lng(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || !v.canConvertToLong() ? null : v.asLong();
    }

    static int integer(JsonNode n, String f, int dflt) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || !v.canConvertToInt() ? dflt : v.asInt();
    }

    static boolean bool(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v != null && v.asBoolean(false);
    }

    static Instant inst(JsonNode n, String f) {
        String s = str(n, f);
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
