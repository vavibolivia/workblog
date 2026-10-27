package com.workblog.ops;

import com.workblog.ops.OpsDtos.Alert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpsAlertsTest {
    private static final long GB = 1024L * 1024 * 1024;

    private static OpsAlerts.Input healthy() {
        return new OpsAlerts.Input(100 * GB, 50 * GB, 200 * 1024 * 1024, 768 * 1024 * 1024, 3, 0, true, null);
    }

    private static List<String> codes(OpsAlerts.Input in) {
        return OpsAlerts.evaluate(in).stream().map(Alert::code).toList();
    }

    @Test
    void healthy_system_has_no_alerts() {
        assertThat(OpsAlerts.evaluate(healthy())).isEmpty();
    }

    @Test
    void disk_below_10_percent_free_warns_but_exactly_10_percent_does_not() {
        OpsAlerts.Input h = healthy();
        assertThat(codes(new OpsAlerts.Input(100 * GB, 9 * GB, h.heapUsed(), h.heapMax(), h.trashCount(), 0, true, null))).containsExactly("DISK_LOW");
        assertThat(codes(new OpsAlerts.Input(100 * GB, 10 * GB, h.heapUsed(), h.heapMax(), h.trashCount(), 0, true, null))).isEmpty();
    }

    @Test
    void unknown_disk_size_does_not_warn() {
        OpsAlerts.Input h = healthy();
        assertThat(codes(new OpsAlerts.Input(0, 0, h.heapUsed(), h.heapMax(), h.trashCount(), 0, true, null))).isEmpty();
    }

    @Test
    void trash_100_or_more_warns() {
        OpsAlerts.Input h = healthy();
        assertThat(codes(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), h.heapUsed(), h.heapMax(), 99, 0, true, null))).isEmpty();
        assertThat(codes(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), h.heapUsed(), h.heapMax(), 100, 0, true, null))).containsExactly("TRASH_MANY");
    }

    @Test
    void ten_or_more_errors_in_an_hour_warns() {
        OpsAlerts.Input h = healthy();
        assertThat(codes(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), h.heapUsed(), h.heapMax(), h.trashCount(), 9, true, null))).isEmpty();
        assertThat(codes(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), h.heapUsed(), h.heapMax(), h.trashCount(), 10, true, null))).containsExactly("ERRORS_BURST");
    }

    @Test
    void memory_90_percent_warns() {
        OpsAlerts.Input h = healthy();
        assertThat(codes(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), 90, 100, h.trashCount(), 0, true, null))).containsExactly("MEMORY_HIGH");
        assertThat(codes(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), 89, 100, h.trashCount(), 0, true, null))).isEmpty();
    }

    @Test
    void db_down_and_cleanup_failure_are_reported_together_db_first() {
        OpsAlerts.Input h = healthy();
        List<Alert> out = OpsAlerts.evaluate(new OpsAlerts.Input(h.diskTotal(), h.diskFree(), h.heapUsed(), h.heapMax(), h.trashCount(), 0, false, "boom"));
        assertThat(out).extracting(Alert::code).containsExactly("DB_DOWN", "CLEANUP_FAILED");
        assertThat(out.get(1).message()).contains("boom");
    }
}
