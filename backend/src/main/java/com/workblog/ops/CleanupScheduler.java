package com.workblog.ops;

import com.workblog.config.WorkblogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 자동 정리(매일 03:30). ① 휴지통에서 N일 지난 글 영구 삭제 ② 글에 연결되지 않은 채 H시간 지난 첨부 삭제.
 * PostService.purge 는 관리자 User 가 필요해서 쓰지 않고, 같은 순서(첨부 파일 → attachments 행 → posts 행)를 네이티브 SQL 로 밟는다.
 * 리비전·댓글·공감은 posts FK 의 ON DELETE CASCADE 로 함께 지워진다. 파일 경로 규칙은 AttachmentService 와 동일: {storage.dir}/{stored_name}.
 */
@Component
public class CleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    /** 마지막 실행 결과(상태 API 에 노출). error 가 null 이면 성공. */
    public record Result(Instant startedAt, Instant finishedAt, boolean manual,
                         int purgedPosts, int deletedOrphans, int deletedFiles, int failedFiles, String error) {}

    private final JdbcTemplate jdbc;
    private final OpsProperties props;
    private final Path root;
    private volatile Result lastResult;

    public CleanupScheduler(JdbcTemplate jdbc, OpsProperties props, WorkblogProperties workblog) {
        this.jdbc = jdbc;
        this.props = props;
        this.root = Path.of(workblog.storage().dir()).toAbsolutePath().normalize();
    }

    public Result lastResult() { return lastResult; }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        if (!props.cleanupEnabled()) {
            log.info("자동 정리 건너뜀(workblog.ops.cleanup-enabled=false)");
            return;
        }
        run(false);
    }

    /** 관리자 수동 실행(POST /api/admin/ops/cleanup). cleanup-enabled 와 무관하게 돈다. */
    public synchronized Result run(boolean manual) {
        Instant start = Instant.now();
        int posts = 0, orphans = 0, files = 0, failed = 0;
        String error = null;
        try {
            // ① 휴지통
            Instant trashCutoff = trashCutoff(start, props.trashRetentionDays());
            List<Long> expired = jdbc.queryForList("SELECT id FROM posts WHERE deleted_at IS NOT NULL AND deleted_at < ?", Long.class, Timestamp.from(trashCutoff));
            for (Long postId : expired) {
                try {
                    List<String> stored = jdbc.queryForList("SELECT stored_name FROM attachments WHERE post_id = ?", String.class, postId);
                    for (String s : stored) { if (deleteFile(s)) files++; else failed++; }
                    jdbc.update("DELETE FROM attachments WHERE post_id = ?", postId);
                    jdbc.update("DELETE FROM posts WHERE id = ?", postId);
                    posts++;
                } catch (RuntimeException e) {
                    log.warn("휴지통 글 {} 영구 삭제 실패: {}", postId, e.toString());
                }
            }
            // ② 고아 첨부
            Instant orphanCutoff = orphanCutoff(start, props.orphanAttachmentHours());
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, stored_name FROM attachments WHERE post_id IS NULL AND created_at < ?", Timestamp.from(orphanCutoff));
            for (var r : rows) {
                if (deleteFile((String) r.get("stored_name"))) files++; else failed++;
                jdbc.update("DELETE FROM attachments WHERE id = ?", ((Number) r.get("id")).longValue());
                orphans++;
            }
        } catch (RuntimeException e) {
            error = e.toString();
            log.error("자동 정리 실패", e);
        }
        Result r = new Result(start, Instant.now(), manual, posts, orphans, files, failed, error);
        lastResult = r;
        log.info("정리 완료({}): 휴지통 글 {}건, 고아 첨부 {}건, 파일 {}개 삭제, 파일 실패 {}개 ({}ms)",
                manual ? "수동" : "자동", posts, orphans, files, failed, r.finishedAt().toEpochMilli() - start.toEpochMilli());
        return r;
    }

    /** 파일이 이미 없으면 성공으로 본다(행만 남은 상태 정리). 저장소 밖 경로는 건너뛴다. */
    private boolean deleteFile(String storedName) {
        if (storedName == null || storedName.isBlank()) return true;
        Path p = root.resolve(storedName).normalize();
        if (!p.startsWith(root)) { log.warn("저장소 밖 경로 무시: {}", storedName); return false; }
        try { Files.deleteIfExists(p); return true; }
        catch (IOException e) { log.warn("첨부 파일 삭제 실패: {}", storedName); return false; }
    }

    /** 휴지통 기준 시각: now - days 일. deleted_at 이 이보다 앞선 글이 대상. */
    static Instant trashCutoff(Instant now, int days) {
        return now.minus(Math.max(days, 0), ChronoUnit.DAYS);
    }

    /** 고아 첨부 기준 시각: now - hours 시간. created_at 이 이보다 앞선 첨부가 대상. */
    static Instant orphanCutoff(Instant now, int hours) {
        return now.minus(Math.max(hours, 0), ChronoUnit.HOURS);
    }
}
