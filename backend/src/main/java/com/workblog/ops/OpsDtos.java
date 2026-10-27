package com.workblog.ops;

import java.time.Instant;
import java.util.List;

/** GET /api/admin/ops/status 응답. 프론트 lib/api.ts 의 OpsStatus 와 1:1. */
public final class OpsDtos {
    private OpsDtos() {}

    public record App(String version, Instant startedAt, long uptimeSeconds, String javaVersion, Instant now) {}
    public record Memory(long usedBytes, long maxBytes) {}
    public record Disk(String path, long totalBytes, long freeBytes) {}
    public record Db(boolean up, String version, String error) {}
    public record Counts(long users, long activeUsers, long posts, long published, long drafts, long trash,
                         long comments, long attachments, long attachmentBytes) {}
    public record Cleanup(boolean enabled, int trashRetentionDays, int orphanAttachmentHours, CleanupScheduler.Result last) {}

    /** 경고 한 건. code 는 안정된 식별자(프론트 테스트·필터용), message 는 화면 문구. */
    public record Alert(String code, String message) {}

    public record StatusResponse(App app, Memory memory, Disk disk, Db db, Counts counts, Cleanup cleanup,
                                 List<ErrorLogBuffer.Entry> recentErrors, List<Alert> alerts) {}
}
