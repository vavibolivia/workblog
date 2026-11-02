package com.workblog.datamove;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DataMoveDtos {
    private DataMoveDtos() {}

    /** 내보내기·가져오기 기록 한 줄(보고서 본문은 상세 조회에서). */
    public record TransferRow(long id, String direction, String dumpKind, String action, String dumpName, String sourceInstance,
                              Instant since, Instant exportedAt, boolean baseline, String status, String actorName,
                              Instant startedAt, Instant finishedAt, String summary) {}

    public record Info(String instanceId, Instant lastExportAt, long importMaxMb, List<TransferRow> history) {}

    public record ExportRequest(Boolean full, Instant since, Boolean markBaseline) {}

    public record Ticket(String ticket, long expiresInSeconds) {}

    public record ManifestSummary(String dumpName, String sourceInstanceId, String kind, String appVersion,
                                  Instant exportedAt, Instant since, Map<String, Long> counts, long totalBytes) {}

    public record UploadResult(String uploadId, ManifestSummary manifest, ImportReport preview) {}

    public record TransferDetail(TransferRow transfer, Object report) {}
}
