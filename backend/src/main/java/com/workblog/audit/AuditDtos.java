package com.workblog.audit;

import java.time.Instant;
import java.util.List;

public final class AuditDtos {
    private AuditDtos() {}

    public record UserRef(Long id, String username, String displayName, String department) {}

    /** 관리자 감사 로그 한 줄 */
    public record ViewLogEntry(Long id, Long postId, String postTitle, boolean postDeleted, UserRef user, Instant viewedAt, PostView.Via via) {}

    /** 작성자용 열람자 한 줄: 기록 횟수(10분 안 반복은 1회), 마지막 열람 시각 */
    public record ViewerRow(UserRef user, long count, Instant lastViewedAt) {}

    /** totalViewers = 작성자를 뺀 서로 다른 열람자 수, viewers = 최근 50명 */
    public record ViewersResponse(Long postId, long totalViewers, List<ViewerRow> viewers) {}
}
