package com.workblog.manmonth;

import com.workblog.common.ApiException;
import com.workblog.manmonth.ManMonthDtos.*;
import com.workblog.user.User;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 공수 표 ↔ 엑셀. 화면과 같은 모양: 왼쪽 프로젝트·이름·소속·역할, 위 2줄 = 월 / 주(월요일 날짜).
 * 가져오기: 주 머리의 날짜로 주를 찾고, (프로젝트 이름, 인원 이름) 으로 행을 찾는다(없으면 만든다). 빈 칸은 '비움'.
 */
@Component
public class ManMonthExcel {
    static final int LEFT = 4;           // A~D: 프로젝트 · 이름 · 소속 · 역할
    static final int HEAD_MONTH = 2, HEAD_WEEK = 3, FIRST_DATA = 4;
    static final String TOTAL_LABEL = "주별 합계";

    private final ManMonthService service;
    private final JdbcTemplate jdbc;

    public ManMonthExcel(ManMonthService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    /** withValues=false 면 같은 구조의 빈 양식. */
    public byte[] export(SheetDetail d, boolean withValues) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("공수");
            CreationHelper ch = wb.getCreationHelper();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(head);
            CellStyle date = wb.createCellStyle();
            date.cloneStyleFrom(head);
            date.setDataFormat(ch.createDataFormat().getFormat("mm/dd"));
            CellStyle num = wb.createCellStyle();
            num.setDataFormat(ch.createDataFormat().getFormat("0.0#"));
            num.setAlignment(HorizontalAlignment.CENTER);
            border(num);
            CellStyle text = wb.createCellStyle();
            border(text);
            CellStyle total = wb.createCellStyle();
            total.cloneStyleFrom(num);
            total.setFont(bold);

            Row r0 = sh.createRow(0);
            r0.createCell(0).setCellValue(d.title());
            r0.getCell(0).setCellStyle(titleStyle(wb));
            sh.createRow(1).createCell(0).setCellValue("시작 " + d.startDate() + " (월) · " + d.weeks() + "주 · 칸 = 그 주 투입률 0~1.0 · 합계 MM = 달마다 (투입률 합 ÷ 그 달 월요일 수) 의 합");

            Row hm = sh.createRow(HEAD_MONTH), hw = sh.createRow(HEAD_WEEK);
            String[] left = {"프로젝트", "이름", "소속", "역할"};
            for (int c = 0; c < LEFT; c++) {
                cell(hm, c, left[c], head);
                cell(hw, c, "", head);
                sh.addMergedRegion(new CellRangeAddress(HEAD_MONTH, HEAD_WEEK, c, c));
            }
            YearMonth cur = null;
            int monthStart = LEFT;
            for (int w = 0; w < d.weeks(); w++) {
                LocalDate mon = d.startDate().plusWeeks(w);
                YearMonth ym = YearMonth.from(mon);
                if (!ym.equals(cur)) {
                    if (cur != null && LEFT + w - 1 > monthStart) sh.addMergedRegion(new CellRangeAddress(HEAD_MONTH, HEAD_MONTH, monthStart, LEFT + w - 1));
                    cur = ym;
                    monthStart = LEFT + w;
                    cell(hm, LEFT + w, ym.getYear() + "-" + String.format("%02d", ym.getMonthValue()), head);
                } else {
                    cell(hm, LEFT + w, "", head);
                }
                Cell c = hw.createCell(LEFT + w);
                c.setCellValue(mon);
                c.setCellStyle(date);
                sh.setColumnWidth(LEFT + w, 7 * 256);
            }
            if (LEFT + d.weeks() - 1 > monthStart) sh.addMergedRegion(new CellRangeAddress(HEAD_MONTH, HEAD_MONTH, monthStart, LEFT + d.weeks() - 1));
            int totalCol = LEFT + d.weeks();
            cell(hm, totalCol, "합계 MM", head);
            cell(hw, totalCol, "", head);
            sh.addMergedRegion(new CellRangeAddress(HEAD_MONTH, HEAD_WEEK, totalCol, totalCol));

            Map<Long, String> projects = new HashMap<>();
            for (ProjectDto p : d.projects()) projects.put(p.id(), p.name());
            List<RowDto> rows = new ArrayList<>(d.rows());
            Map<Long, Integer> porder = new HashMap<>();
            for (int i = 0; i < d.projects().size(); i++) porder.put(d.projects().get(i).id(), i);
            rows.sort(Comparator.comparing((RowDto r) -> porder.getOrDefault(r.projectId(), 0)).thenComparing(RowDto::sortOrder).thenComparing(RowDto::id));
            int ri = FIRST_DATA;
            double[] weekSum = new double[d.weeks()];
            for (RowDto r : rows) {
                Row row = sh.createRow(ri++);
                cell(row, 0, projects.getOrDefault(r.projectId(), ""), text);
                cell(row, 1, r.personName(), text);
                cell(row, 2, nz(r.department()), text);
                cell(row, 3, nz(r.role()), text);
                for (int w = 0; w < d.weeks(); w++) {
                    Cell c = row.createCell(LEFT + w);
                    c.setCellStyle(num);
                    BigDecimal v = withValues ? r.cells().get(w) : null;
                    if (v != null) {
                        c.setCellValue(v.doubleValue());
                        weekSum[w] += v.doubleValue();
                    }
                }
                Cell t = row.createCell(totalCol);
                t.setCellStyle(total);
                t.setCellValue(withValues ? totalMm(d.startDate(), d.weeks(), r.cells()) : 0);
            }
            Row tr = sh.createRow(ri);
            cell(tr, 0, TOTAL_LABEL, head);
            for (int c = 1; c < LEFT; c++) cell(tr, c, "", head);
            for (int w = 0; w < d.weeks(); w++) {
                Cell c = tr.createCell(LEFT + w);
                c.setCellStyle(total);
                c.setCellValue(Math.round(weekSum[w] * 100) / 100.0);
            }
            sh.setColumnWidth(0, 18 * 256);
            sh.setColumnWidth(1, 12 * 256);
            sh.setColumnWidth(2, 14 * 256);
            sh.setColumnWidth(3, 12 * 256);
            sh.setColumnWidth(totalCol, 10 * 256);
            sh.createFreezePane(LEFT, FIRST_DATA);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("엑셀을 만들지 못했습니다.", e);
        }
    }

    /** 엑셀 내용을 표에 반영. 파일에 있는 행만 바꾸고, 파일에 없는 행은 그대로 둔다. */
    @Transactional
    public ImportResult importInto(long sheetId, InputStream in, User actor) {
        SheetDetail d = service.get(sheetId, actor);
        if (!d.canEdit()) throw ApiException.forbidden("이 표의 편집자가 아닙니다.");
        List<String> warnings = new ArrayList<>();
        int projectsCreated = 0, rowsCreated = 0, set = 0, cleared = 0;
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            // 주 머리: 날짜 칸이 가장 많은 줄
            int headRow = -1, best = 0;
            for (int r = 0; r <= Math.min(sh.getLastRowNum(), 15); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                int n = 0;
                for (int c = LEFT; c < row.getLastCellNum(); c++) if (dateOf(row.getCell(c)) != null) n++;
                if (n > best) { best = n; headRow = r; }
            }
            if (headRow < 0) throw ApiException.badRequest("주 머리(월요일 날짜) 줄을 찾지 못했습니다. 이 표에서 내려받은 양식을 쓰세요.");
            Map<Integer, Integer> colWeek = new LinkedHashMap<>();
            Row hw = sh.getRow(headRow);
            for (int c = LEFT; c < hw.getLastCellNum(); c++) {
                LocalDate dt = dateOf(hw.getCell(c));
                if (dt == null) continue;
                LocalDate mon = dt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                long w = ChronoUnit.WEEKS.between(d.startDate(), mon);
                if (w < 0 || w >= d.weeks()) { warnings.add(dt + " 열은 표 기간 밖이라 건너뜀"); continue; }
                colWeek.put(c, (int) w);
            }
            Map<String, Long> projectIds = new HashMap<>();
            for (ProjectDto p : d.projects()) projectIds.put(p.name().trim().toLowerCase(Locale.ROOT), p.id());
            Map<String, Long> rowIds = new HashMap<>();
            for (RowDto r : d.rows()) rowIds.putIfAbsent(r.projectId() + "|" + r.personName().trim().toLowerCase(Locale.ROOT), r.id());
            DataFormatter fmt = new DataFormatter();
            String lastProject = null;
            for (int r = headRow + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String project = fmt.formatCellValue(row.getCell(0)).trim();
                String name = fmt.formatCellValue(row.getCell(1)).trim();
                if (TOTAL_LABEL.equals(project)) break;
                if (project.isEmpty()) project = lastProject;          // 병합·빈 칸이면 위 프로젝트
                if (name.isEmpty() || project == null) continue;
                lastProject = project;
                Long pid = projectIds.get(project.toLowerCase(Locale.ROOT));
                if (pid == null) {
                    service.addProject(sheetId, new ProjectRequest(project, null), actor);
                    pid = jdbc.queryForObject("SELECT MAX(id) FROM mm_projects WHERE sheet_id = ? AND name = ?", Long.class, sheetId, project);
                    projectIds.put(project.toLowerCase(Locale.ROOT), pid);
                    projectsCreated++;
                }
                String key = pid + "|" + name.toLowerCase(Locale.ROOT);
                Long rid = rowIds.get(key);
                if (rid == null) {
                    List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE display_name = ? AND active = TRUE", Long.class, name);
                    String dept = fmt.formatCellValue(row.getCell(2)).trim();
                    String role = fmt.formatCellValue(row.getCell(3)).trim();
                    service.addRow(sheetId, new RowRequest(pid, users.size() == 1 ? users.get(0) : null, name, dept, role, null), actor);
                    rid = jdbc.queryForObject("SELECT MAX(id) FROM mm_rows WHERE sheet_id = ? AND project_id = ?", Long.class, sheetId, pid);
                    rowIds.put(key, rid);
                    rowsCreated++;
                }
                for (Map.Entry<Integer, Integer> e : colWeek.entrySet()) {
                    try {
                        BigDecimal n = ManMonthService.normalize(valueOf(row.getCell(e.getKey()), fmt));
                        if (n == null) cleared += jdbc.update("DELETE FROM mm_cells WHERE row_id = ? AND week = ?", rid, e.getValue());
                        else { service.upsert(rid, e.getValue(), n); set++; }
                    } catch (ApiException ex) {
                        warnings.add((r + 1) + "행 " + name + ": " + ex.getMessage());
                    }
                }
            }
        } catch (IOException | org.apache.poi.EncryptedDocumentException e) {
            throw ApiException.badRequest("엑셀 파일을 읽지 못했습니다(.xlsx).");
        }
        service.touch(sheetId);
        service.log(sheetId, actor, "엑셀 가져오기: 프로젝트 +" + projectsCreated + " · 인원 +" + rowsCreated + " · " + set + "칸 · 비움 " + cleared + "칸");
        return new ImportResult(projectsCreated, rowsCreated, set, cleared, warnings.size() > 50 ? warnings.subList(0, 50) : warnings);
    }

    /** 합계 MM = 달마다 (그 달 주 투입률 합 ÷ 그 달 월요일 수) 의 합. 1.0 으로 한 달 내내 = 1 MM. */
    static double totalMm(LocalDate start, int weeks, Map<Integer, BigDecimal> cells) {
        Map<YearMonth, Double> sum = new HashMap<>();
        for (Map.Entry<Integer, BigDecimal> e : cells.entrySet()) {
            if (e.getKey() >= weeks) continue;
            sum.merge(YearMonth.from(start.plusWeeks(e.getKey())), e.getValue().doubleValue(), Double::sum);
        }
        double t = 0;
        for (Map.Entry<YearMonth, Double> e : sum.entrySet()) t += e.getValue() / mondays(e.getKey());
        return Math.round(t * 100) / 100.0;
    }

    static int mondays(YearMonth ym) {
        LocalDate d = ym.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        int n = 0;
        while (YearMonth.from(d).equals(ym)) { n++; d = d.plusWeeks(1); }
        return n;
    }

    private static LocalDate dateOf(Cell c) {
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) return c.getLocalDateTimeCellValue().toLocalDate();
            if (c.getCellType() == CellType.STRING) {
                String s = c.getStringCellValue().trim();
                if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static BigDecimal valueOf(Cell c, DataFormatter fmt) {
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue());
        if (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue());
        String s = fmt.formatCellValue(c).trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("숫자가 아닌 값: " + s);
        }
    }

    private static void cell(Row r, int c, String v, CellStyle st) {
        Cell x = r.createCell(c);
        x.setCellValue(v);
        x.setCellStyle(st);
    }

    private static CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        return s;
    }

    private static void border(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
