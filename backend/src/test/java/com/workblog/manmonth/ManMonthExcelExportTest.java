package com.workblog.manmonth;

import com.workblog.manmonth.ManMonthDtos.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 엑셀 내려받기: 프로젝트 소계 줄 · 메모 시트 · 이벤트 시트(표 기간 안만). */
class ManMonthExcelExportTest {

    @Test
    void subtotalsMemoAndEventSheets() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 2);
        List<ProjectDto> projects = List.of(new ProjectDto(1L, "AirDeck", 0), new ProjectDto(2L, "Portal", 1));
        List<RowDto> rows = List.of(
                new RowDto(10L, 1L, null, "김", null, null, 0, Map.of(0, new BigDecimal("0.5"), 1, BigDecimal.ONE), Map.of(1, "설계 검토")),
                new RowDto(11L, 1L, null, "이", null, null, 1, Map.of(0, new BigDecimal("0.25")), Map.of()),
                new RowDto(12L, 2L, null, "박", null, null, 0, Map.of(2, BigDecimal.ONE), Map.of()));
        List<EventDto> events = List.of(
                new EventDto(1L, LocalDate.of(2026, 11, 10), null, EventKind.RELEASE, "1차 배포", "AirDeck", null, "a", Instant.now(), true),
                new EventDto(2L, LocalDate.of(2027, 5, 1), null, EventKind.ETC, "기간 밖", null, null, "a", Instant.now(), true));
        SheetDetail d = new SheetDetail(1L, "시험", null, start, 4, false, 1L, "a", Instant.now(), true, true,
                List.of(), projects, rows, List.of(), events);

        byte[] x = new ManMonthExcel(null, null).export(d, true);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            DataFormatter f = new DataFormatter();
            Sheet sh = wb.getSheet("공수");
            // 4 = 김, 5 = 이, 6 = AirDeck 소계, 7 = 박, 8 = Portal 소계, 9 = 주별 합계
            assertThat(f.formatCellValue(sh.getRow(6).getCell(0))).isEqualTo("AirDeck" + ManMonthExcel.SUBTOTAL_SUFFIX);
            assertThat(sh.getRow(6).getCell(ManMonthExcel.LEFT).getNumericCellValue()).isEqualTo(0.75);
            assertThat(sh.getRow(6).getCell(ManMonthExcel.LEFT + 1).getNumericCellValue()).isEqualTo(1.0);
            assertThat(f.formatCellValue(sh.getRow(8).getCell(0))).isEqualTo("Portal" + ManMonthExcel.SUBTOTAL_SUFFIX);
            assertThat(f.formatCellValue(sh.getRow(9).getCell(0))).isEqualTo(ManMonthExcel.TOTAL_LABEL);
            assertThat(sh.getRow(9).getCell(ManMonthExcel.LEFT).getNumericCellValue()).isEqualTo(0.75);

            Sheet memo = wb.getSheet("메모");
            assertThat(memo.getLastRowNum()).isEqualTo(1);
            assertThat(f.formatCellValue(memo.getRow(1).getCell(2))).isEqualTo("2026-11-09");
            assertThat(f.formatCellValue(memo.getRow(1).getCell(4))).isEqualTo("설계 검토");

            Sheet ev = wb.getSheet("이벤트");
            assertThat(ev.getLastRowNum()).isEqualTo(1);
            assertThat(f.formatCellValue(ev.getRow(1).getCell(2))).isEqualTo("1차 배포");
            assertThat(f.formatCellValue(ev.getRow(0).getCell(2))).isEqualTo("제목");   // 종류 열 없음
        }
    }

    @Test
    void blankTemplateHasNoListSheets() throws Exception {
        SheetDetail d = new SheetDetail(1L, "빈 양식", null, LocalDate.of(2026, 11, 2), 2, false, 1L, "a", Instant.now(), true, true,
                List.of(), List.of(new ProjectDto(1L, "P", 0)), List.of(new RowDto(1L, 1L, null, "김", null, null, 0, Map.of(), Map.of())), List.of(), List.of());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(new ManMonthExcel(null, null).export(d, false)))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
        }
    }

    @Test
    void exportSummaryTableIsMonthly() {
        // 2026-11-02 시작 6주: 11월(월요일 5개) 1.0 × 5주 = 1 MM, 12월 첫 주 0.5 → 0.5/4 = 0.13
        SheetDetail d = new SheetDetail(1L, "요약 <표>", null, LocalDate.of(2026, 11, 2), 6, false, 1L, "a", Instant.now(), true, true,
                List.of(), List.of(new ProjectDto(1L, "P", 0)),
                List.of(new RowDto(1L, 1L, null, "김", null, "개발", 0,
                        Map.of(0, BigDecimal.ONE, 1, BigDecimal.ONE, 2, BigDecimal.ONE, 3, BigDecimal.ONE, 4, BigDecimal.ONE, 5, new BigDecimal("0.5")), Map.of())),
                List.of(), List.of());
        String t = MmExportTables.table(d);
        assertThat(t).contains("<th>26.11</th><th>26.12</th>").contains("<td>1</td><td>0.13</td><td><strong>1.13</strong></td>")
                .contains("요약 &lt;표&gt;").contains(MmExportTables.MARK);
    }
}
