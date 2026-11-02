package com.workblog.manmonth;

import com.workblog.common.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManMonthCalcTest {

    @Test
    void mondaysPerMonth() {
        assertThat(ManMonthExcel.mondays(YearMonth.of(2026, 11))).isEqualTo(5);   // 2·9·16·23·30
        assertThat(ManMonthExcel.mondays(YearMonth.of(2026, 2))).isEqualTo(4);
    }

    @Test
    void fullMonthAtOneIsOneManMonth() {
        // 2026-11-02 부터 11월 월요일 5주를 1.0 → 1 MM, 12월 첫 주 0.5 → 0.5/4 = 0.125
        Map<Integer, BigDecimal> cells = Map.of(0, BigDecimal.ONE, 1, BigDecimal.ONE, 2, BigDecimal.ONE, 3, BigDecimal.ONE, 4, BigDecimal.ONE,
                5, new BigDecimal("0.5"));
        assertThat(ManMonthExcel.totalMm(LocalDate.of(2026, 11, 2), 8, cells)).isEqualTo(1.13);
    }

    @Test
    void cellsOutsideTheSheetAreIgnored() {
        assertThat(ManMonthExcel.totalMm(LocalDate.of(2026, 11, 2), 2, Map.of(5, BigDecimal.ONE))).isZero();
    }

    @Test
    void valueRange() {
        assertThat(ManMonthService.normalize(null)).isNull();
        assertThat(ManMonthService.normalize(BigDecimal.ZERO)).isNull();
        assertThat(ManMonthService.normalize(new BigDecimal("0.333"))).isEqualByComparingTo("0.33");
        assertThat(ManMonthService.normalize(new BigDecimal("1"))).isEqualByComparingTo("1.00");
        assertThatThrownBy(() -> ManMonthService.normalize(new BigDecimal("1.01"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ManMonthService.normalize(new BigDecimal("-0.1"))).isInstanceOf(ApiException.class);
    }

    @Test
    void startIsSnappedToMonday() {
        assertThat(ManMonthService.monday(LocalDate.of(2026, 11, 4))).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(ManMonthService.monday(LocalDate.of(2026, 11, 8))).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(ManMonthService.monday(LocalDate.of(2026, 11, 2))).isEqualTo(LocalDate.of(2026, 11, 2));
    }
}
