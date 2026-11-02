package com.workblog.datamove;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 미리 보기·적용 결과. 화면에 보이고 data_transfers.report 에 JSON 으로 남는다. */
public class ImportReport {
    public enum Action { CREATE, UPDATE, SKIP, CONFLICT, FAIL }

    public static final int MAX_ITEMS = 2000;

    public static class Counts {
        public long created, updated, skipped, conflict, failed;
    }

    public record Item(String entity, Action action, String sourceId, Long targetId, String label, String reason) {}

    public String dumpName;
    public String sourceInstanceId;
    public String kind;
    public Instant exportedAt;
    public Instant since;
    public boolean applied;
    /** OK · FAILED */
    public String status = "OK";
    public final List<String> completedStages = new ArrayList<>();
    public String failedStage;
    public String error;
    /** 실패한 단계에서 마지막으로 커밋된 원본 id(어디까지 반영됐는지) */
    public String lastCommitted;
    public final Map<String, Counts> counts = new LinkedHashMap<>();
    public final List<Item> items = new ArrayList<>();
    public int itemsOmitted;

    Counts counts(String entity) { return counts.computeIfAbsent(entity, k -> new Counts()); }

    void add(String entity, Action a, Object sourceId, Long targetId, String label, String reason, boolean list) {
        Counts c = counts(entity);
        switch (a) {
            case CREATE -> c.created++;
            case UPDATE -> c.updated++;
            case SKIP -> c.skipped++;
            case CONFLICT -> c.conflict++;
            case FAIL -> c.failed++;
        }
        // 충돌·실패는 항상, 나머지는 요청한 것만 목록에 남긴다(많으면 잘라서 개수만)
        if (!list && a != Action.CONFLICT && a != Action.FAIL) return;
        if (items.size() >= MAX_ITEMS) { itemsOmitted++; return; }
        items.add(new Item(entity, a, sourceId == null ? null : String.valueOf(sourceId), targetId, DumpFormat.cut(label, 200), reason));
    }
}
