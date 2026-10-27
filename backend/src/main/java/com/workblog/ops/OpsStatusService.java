package com.workblog.ops;

import com.workblog.config.WorkblogProperties;
import com.workblog.ops.OpsDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 관리자 상태 화면용 집계. 모든 조회는 실패해도 전체 응답이 깨지지 않도록 부분 값(0, error)으로 떨어진다. */
@Service
public class OpsStatusService {
    private static final Logger log = LoggerFactory.getLogger(OpsStatusService.class);
    static final String VERSION = "workblog-backend-0.1.0";
    static final int RECENT_ERRORS = 20;

    private final JdbcTemplate jdbc;
    private final OpsProperties props;
    private final CleanupScheduler cleanup;
    private final Path storageRoot;

    public OpsStatusService(JdbcTemplate jdbc, OpsProperties props, CleanupScheduler cleanup, WorkblogProperties workblog) {
        this.jdbc = jdbc;
        this.props = props;
        this.cleanup = cleanup;
        this.storageRoot = Path.of(workblog.storage().dir()).toAbsolutePath().normalize();
    }

    public StatusResponse status() {
        Instant now = Instant.now();
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        App app = new App(VERSION, started, Duration.between(started, now).getSeconds(), System.getProperty("java.version"), now);

        Runtime rt = Runtime.getRuntime();
        Memory mem = new Memory(rt.totalMemory() - rt.freeMemory(), rt.maxMemory());

        Disk disk = disk();
        Db db = db();
        Counts counts = db.up() ? counts() : new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0);
        CleanupScheduler.Result last = cleanup.lastResult();
        Cleanup cl = new Cleanup(props.cleanupEnabled(), props.trashRetentionDays(), props.orphanAttachmentHours(), last);

        int errorsLastHour = ErrorLogBuffer.countSince(now.minus(Duration.ofHours(1)));
        List<Alert> alerts = OpsAlerts.evaluate(new OpsAlerts.Input(disk.totalBytes(), disk.freeBytes(), mem.usedBytes(), mem.maxBytes(),
                counts.trash(), errorsLastHour, db.up(), last == null ? null : last.error()));

        return new StatusResponse(app, mem, disk, db, counts, cl, ErrorLogBuffer.recent(RECENT_ERRORS), alerts);
    }

    private Disk disk() {
        try {
            Path p = Files.exists(storageRoot) ? storageRoot : storageRoot.getParent();
            FileStore fs = Files.getFileStore(p);
            return new Disk(storageRoot.toString(), fs.getTotalSpace(), fs.getUsableSpace());
        } catch (IOException | RuntimeException e) {
            log.warn("디스크 정보 조회 실패: {}", e.toString());
            return new Disk(storageRoot.toString(), 0, 0);
        }
    }

    private Db db() {
        try {
            String v = jdbc.queryForObject("SELECT version()", String.class);
            // "PostgreSQL 14.13 (Ubuntu ...) on x86_64..." → "PostgreSQL 14.13"
            if (v != null) { int i = v.indexOf(" on "); int j = v.indexOf(" ("); int cut = Math.min(i < 0 ? v.length() : i, j < 0 ? v.length() : j); v = v.substring(0, cut); }
            return new Db(true, v, null);
        } catch (RuntimeException e) {
            return new Db(false, null, e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString()));
        }
    }

    private Counts counts() {
        try {
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT (SELECT count(*) FROM users)                                                          AS users,
                           (SELECT count(*) FROM users WHERE active)                                             AS active_users,
                           (SELECT count(*) FROM posts WHERE deleted_at IS NULL)                                 AS posts,
                           (SELECT count(*) FROM posts WHERE deleted_at IS NULL AND status = 'PUBLISHED')        AS published,
                           (SELECT count(*) FROM posts WHERE deleted_at IS NULL AND status = 'DRAFT')            AS drafts,
                           (SELECT count(*) FROM posts WHERE deleted_at IS NOT NULL)                             AS trash,
                           (SELECT count(*) FROM comments WHERE deleted_at IS NULL)                              AS comments,
                           (SELECT count(*) FROM attachments)                                                    AS attachments,
                           (SELECT coalesce(sum(size_bytes), 0) FROM attachments)                                AS attachment_bytes
                    """);
            return new Counts(n(r, "users"), n(r, "active_users"), n(r, "posts"), n(r, "published"), n(r, "drafts"),
                    n(r, "trash"), n(r, "comments"), n(r, "attachments"), n(r, "attachment_bytes"));
        } catch (RuntimeException e) {
            log.warn("집계 조회 실패: {}", e.toString());
            return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private static long n(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v instanceof Number num ? num.longValue() : 0;
    }
}
