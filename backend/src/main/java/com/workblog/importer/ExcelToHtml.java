package com.workblog.importer;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/**
 * 엑셀(.xlsx/.xlsm/.xls) → 표 HTML. 시트마다 표 하나(시트가 여러 개면 시트 이름을 소제목으로).
 * 셀 표시값(날짜·숫자 서식, 수식 결과), 병합 셀(colspan/rowspan), 굵게·글자색·채우기색·가로 정렬을 옮긴다.
 * 첫 번째 내용 행은 머리글(th)로 둔다 — 에디터의 엑셀 삽입과 같은 규칙.
 */
final class ExcelToHtml {
    static final int MAX_SHEETS = 20, MAX_ROWS = 1000, MAX_COLS = 50;

    private ExcelToHtml() {}

    static String convert(byte[] data, List<String> warnings) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            DataFormatter fmt = new DataFormatter(Locale.KOREA);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            List<Integer> sheets = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (!wb.isSheetHidden(i) && !wb.isSheetVeryHidden(i)) sheets.add(i);
            }
            if (sheets.size() > MAX_SHEETS) {
                warnings.add("시트가 " + sheets.size() + "개라 앞의 " + MAX_SHEETS + "개만 가져왔습니다.");
                sheets = sheets.subList(0, MAX_SHEETS);
            }
            List<String[]> tables = new ArrayList<>(); // [시트 이름, 표]
            for (int idx : sheets) {
                Sheet sh = wb.getSheetAt(idx);
                String table = sheetToTable(wb, sh, fmt, eval, warnings);
                if (table != null) tables.add(new String[]{sh.getSheetName(), table});
            }
            if (tables.isEmpty()) warnings.add("엑셀에 내용이 있는 시트가 없습니다.");
            StringBuilder out = new StringBuilder();
            for (String[] t : tables) {
                if (tables.size() > 1) out.append("<h2>").append(esc(t[0])).append("</h2>"); // 내용 있는 시트가 여럿일 때만 시트 제목
                out.append(t[1]).append("<p></p>");
            }
            return out.toString();
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new IOException("암호가 걸린 엑셀은 가져올 수 없습니다. 암호를 풀고 다시 올려 주세요.", e);
        }
    }

    private static String sheetToTable(Workbook wb, Sheet sh, DataFormatter fmt, FormulaEvaluator eval, List<String> warnings) {
        // 사용 범위: 값이 있는 첫/마지막 행, 마지막 열
        int first = -1, last = -1, maxCol = -1;
        for (Row r : sh) {
            int lastCell = -1;
            for (Cell c : r) {
                if (!text(c, fmt, eval).isBlank()) lastCell = Math.max(lastCell, c.getColumnIndex());
            }
            if (lastCell >= 0) {
                if (first < 0) first = r.getRowNum();
                last = r.getRowNum();
                maxCol = Math.max(maxCol, lastCell);
            }
        }
        if (first < 0) return null;
        // 병합 영역도 범위에 포함
        Map<Long, CellRangeAddress> mergeAt = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        for (CellRangeAddress m : sh.getMergedRegions()) {
            if (m.getFirstRow() > last || m.getLastRow() < first) continue;
            mergeAt.put(key(m.getFirstRow(), m.getFirstColumn()), m);
            maxCol = Math.max(maxCol, Math.min(m.getLastColumn(), MAX_COLS - 1));
            for (int r = m.getFirstRow(); r <= m.getLastRow(); r++)
                for (int c = m.getFirstColumn(); c <= m.getLastColumn(); c++)
                    if (r != m.getFirstRow() || c != m.getFirstColumn()) covered.add(key(r, c));
        }
        if (last - first + 1 > MAX_ROWS) {
            warnings.add("'" + sh.getSheetName() + "' 시트는 " + (last - first + 1) + "행이라 앞의 " + MAX_ROWS + "행만 가져왔습니다. 전체는 첨부한 원본을 보세요.");
            last = first + MAX_ROWS - 1;
        }
        if (maxCol + 1 > MAX_COLS) {
            warnings.add("'" + sh.getSheetName() + "' 시트는 " + (maxCol + 1) + "열이라 앞의 " + MAX_COLS + "열만 가져왔습니다.");
            maxCol = MAX_COLS - 1;
        }
        StringBuilder t = new StringBuilder("<table class=\"rich-table\" data-sheet=\"").append(esc(sh.getSheetName())).append("\"><tbody>");
        for (int r = first; r <= last; r++) {
            Row row = sh.getRow(r);
            String tag = r == first ? "th" : "td";
            t.append("<tr>");
            for (int c = 0; c <= maxCol; c++) {
                if (covered.contains(key(r, c))) continue;
                Cell cell = row == null ? null : row.getCell(c);
                t.append('<').append(tag);
                CellRangeAddress m = mergeAt.get(key(r, c));
                if (m != null) {
                    int cs = Math.min(m.getLastColumn(), maxCol) - m.getFirstColumn() + 1;
                    int rs = Math.min(m.getLastRow(), last) - m.getFirstRow() + 1;
                    if (cs > 1) t.append(" colspan=\"").append(cs).append('"');
                    if (rs > 1) t.append(" rowspan=\"").append(rs).append('"');
                }
                String style = cell == null ? "" : style(wb, cell.getCellStyle());
                if (!style.isEmpty()) t.append(" style=\"").append(style).append('"');
                t.append("><p>");
                if (cell != null) {
                    String v = esc(text(cell, fmt, eval)).replace("\n", "<br>");
                    boolean bold = isBold(wb, cell.getCellStyle()) && r != first;
                    t.append(bold && !v.isEmpty() ? "<strong>" + v + "</strong>" : v);
                }
                t.append("</p></").append(tag).append('>');
            }
            t.append("</tr>");
        }
        return t.append("</tbody></table>").toString();
    }

    private static String text(Cell c, DataFormatter fmt, FormulaEvaluator eval) {
        try {
            return fmt.formatCellValue(c, eval).trim();
        } catch (RuntimeException e) {
            // 외부 참조 등 계산할 수 없는 수식은 캐시된 값을 쓴다
            try { return fmt.formatCellValue(c).trim(); } catch (RuntimeException e2) { return ""; }
        }
    }

    private static boolean isBold(Workbook wb, CellStyle cs) {
        return cs != null && wb.getFontAt(cs.getFontIndex()).getBold();
    }

    private static String style(Workbook wb, CellStyle cs) {
        if (cs == null) return "";
        StringBuilder s = new StringBuilder();
        switch (cs.getAlignment()) {
            case CENTER, CENTER_SELECTION -> s.append("text-align:center;");
            case RIGHT -> s.append("text-align:right;");
            default -> { }
        }
        String fill = null, color = null;
        if (cs instanceof XSSFCellStyle x) {
            if (x.getFillPattern() == FillPatternType.SOLID_FOREGROUND) fill = hex(x.getFillForegroundXSSFColor());
            Font f = wb.getFontAt(x.getFontIndex());
            if (f instanceof XSSFFont xf) color = hex(xf.getXSSFColor());
        } else if (cs instanceof HSSFCellStyle h && wb instanceof HSSFWorkbook hw) {
            if (h.getFillPattern() == FillPatternType.SOLID_FOREGROUND) fill = hex(hw.getCustomPalette().getColor(h.getFillForegroundColor()));
            HSSFFont f = h.getFont(hw);
            color = hex(hw.getCustomPalette().getColor(f.getColor()));
        }
        if (fill != null && !fill.equals("#ffffff")) s.append("background-color:").append(fill).append(';');
        if (color != null && !color.equals("#000000")) s.append("color:").append(color).append(';');
        return s.toString();
    }

    private static String hex(XSSFColor c) {
        if (c == null || c.isAuto()) return null;
        byte[] rgb = c.getRGBWithTint();
        if (rgb == null) rgb = c.getRGB();
        if (rgb == null || rgb.length < 3) return null;
        int o = rgb.length == 4 ? 1 : 0;
        return String.format("#%02x%02x%02x", rgb[o] & 0xff, rgb[o + 1] & 0xff, rgb[o + 2] & 0xff);
    }

    private static String hex(HSSFColor c) {
        if (c == null || c.getIndex() == HSSFColor.HSSFColorPredefined.AUTOMATIC.getIndex()) return null;
        short[] t = c.getTriplet();
        return String.format("#%02x%02x%02x", t[0], t[1], t[2]);
    }

    private static long key(int r, int c) { return ((long) r << 16) | c; }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
