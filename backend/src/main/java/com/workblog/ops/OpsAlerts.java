package com.workblog.ops;

import com.workblog.ops.OpsDtos.Alert;

import java.util.ArrayList;
import java.util.List;

/** 상태 경고 판정. 기준은 상수로, 입력은 숫자만 받아 Spring 없이 단위 테스트할 수 있게 둔다. */
final class OpsAlerts {
    private OpsAlerts() {}

    /** 디스크 여유 비율이 이 미만이면 경고. */
    static final double DISK_FREE_WARN_RATIO = 0.10;
    /** 휴지통 글이 이 수 이상이면 경고. */
    static final long TRASH_WARN_COUNT = 100;
    /** 최근 1시간 WARN 이상 로그가 이 수 이상이면 경고. */
    static final int ERRORS_PER_HOUR_WARN = 10;
    /** JVM 힙 사용률이 이 이상이면 경고. */
    static final double MEMORY_USED_WARN_RATIO = 0.90;

    record Input(long diskTotal, long diskFree, long heapUsed, long heapMax, long trashCount,
                 int errorsLastHour, boolean dbUp, String lastCleanupError) {}

    static List<Alert> evaluate(Input in) {
        List<Alert> out = new ArrayList<>();
        if (!in.dbUp()) out.add(new Alert("DB_DOWN", "데이터베이스에 연결할 수 없습니다."));
        if (in.diskTotal() > 0 && (double) in.diskFree() / in.diskTotal() < DISK_FREE_WARN_RATIO) {
            out.add(new Alert("DISK_LOW", String.format("첨부 저장소 디스크 여유가 %.1f%% 입니다(기준 %d%% 미만).",
                    100.0 * in.diskFree() / in.diskTotal(), (int) (DISK_FREE_WARN_RATIO * 100))));
        }
        if (in.heapMax() > 0 && (double) in.heapUsed() / in.heapMax() >= MEMORY_USED_WARN_RATIO) {
            out.add(new Alert("MEMORY_HIGH", String.format("JVM 메모리 사용률이 %.0f%% 입니다(기준 %d%% 이상).",
                    100.0 * in.heapUsed() / in.heapMax(), (int) (MEMORY_USED_WARN_RATIO * 100))));
        }
        if (in.trashCount() >= TRASH_WARN_COUNT) {
            out.add(new Alert("TRASH_MANY", "휴지통에 글이 " + in.trashCount() + "건 있습니다(기준 " + TRASH_WARN_COUNT + "건 이상)."));
        }
        if (in.errorsLastHour() >= ERRORS_PER_HOUR_WARN) {
            out.add(new Alert("ERRORS_BURST", "최근 1시간 오류·경고 로그가 " + in.errorsLastHour() + "건입니다(기준 " + ERRORS_PER_HOUR_WARN + "건 이상)."));
        }
        if (in.lastCleanupError() != null && !in.lastCleanupError().isBlank()) {
            out.add(new Alert("CLEANUP_FAILED", "마지막 자동 정리가 실패했습니다: " + in.lastCleanupError()));
        }
        return out;
    }
}
