package com.workblog.manmonth;

import com.workblog.common.ApiException;
import com.workblog.manmonth.ManMonthDtos.*;
import com.workblog.user.User;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 글 내보내기(Word·HTML·Markdown)용: 본문의 공수 표 링크(`/mm/번호`)가 한 문단을 차지하면 그 아래에 표의 월별 MM 요약표를 넣는다.
 * 화면에서는 링크가 살아 있는 표로 보이지만 파일에는 표가 없으므로, 내보낸 시점의 값을 담는다(주 단위는 너무 넓어 달 단위로).
 */
@Component
public class MmExportTables {
    private static final Pattern MM_LINK = Pattern.compile("^(?:https?://[^/]+)?/mm/(\\d+)/?$");
    /** 한 글에 표가 아주 많아도 내보내기가 느려지지 않게 */
    private static final int MAX_TABLES = 5;
    /** 넣은 표 표시(마크다운 글을 내보낼 때 원문 대신 바뀐 HTML 을 쓰게) */
    public static final String MARK = "data-mm-export";

    private final ManMonthService service;

    public MmExportTables(ManMonthService service) {
        this.service = service;
    }

    public String expand(String html, User viewer) {
        if (html == null || !html.contains("/mm/")) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        int n = 0;
        for (Element a : doc.select("a[href]")) {
            Matcher m = MM_LINK.matcher(a.attr("href").trim());
            if (!m.matches()) continue;
            Element p = a.parent();
            // 링크 혼자 있는 문단만(문장 속 링크는 그대로 둔다)
            if (p == null || !p.tagName().equals("p") || !p.text().trim().equals(a.text().trim())) continue;
            if (n >= MAX_TABLES) break;
            SheetDetail d;
            try {
                d = service.get(Long.parseLong(m.group(1)), viewer);
            } catch (ApiException | NumberFormatException e) {
                continue;   // 지워진 표 등 — 링크만 남긴다
            }
            p.after(table(d));
            n++;
        }
        return n == 0 ? html : doc.body().html();
    }

    static String table(SheetDetail d) {
        List<YearMonth> months = new ArrayList<>();
        for (int w = 0; w < d.weeks(); w++) {
            YearMonth ym = YearMonth.from(d.startDate().plusWeeks(w));
            if (months.isEmpty() || !months.get(months.size() - 1).equals(ym)) months.add(ym);
        }
        Map<Long, String> pname = new HashMap<>();
        Map<Long, Integer> porder = new HashMap<>();
        for (ProjectDto p : d.projects()) { pname.put(p.id(), p.name()); porder.put(p.id(), porder.size()); }
        List<RowDto> rows = new ArrayList<>(d.rows());
        rows.sort(Comparator.comparing((RowDto r) -> porder.getOrDefault(r.projectId(), 0)).thenComparing(RowDto::sortOrder).thenComparing(RowDto::id));

        LocalDate end = d.startDate().plusWeeks(d.weeks()).minusDays(1);
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>").append(esc(d.title())).append("</strong> — ").append(d.startDate()).append(" ~ ").append(end)
          .append(" · 월별 MM(그 달 투입률 합 ÷ 그 달 월요일 수) · 내보낸 때 기준</p>");
        sb.append("<table ").append(MARK).append("=\"1\"><thead><tr><th>프로젝트</th><th>이름</th><th>역할</th>");
        for (YearMonth ym : months) sb.append("<th>").append(ym.getYear() % 100).append('.').append(String.format("%02d", ym.getMonthValue())).append("</th>");
        sb.append("<th>합계 MM</th></tr></thead><tbody>");
        double[] colSum = new double[months.size()];
        double all = 0;
        if (rows.isEmpty()) sb.append("<tr><td colspan=\"").append(months.size() + 4).append("\">(인원 없음)</td></tr>");
        for (RowDto r : rows) {
            double[] mm = monthly(d.startDate(), d.weeks(), r.cells(), months);
            double t = 0;
            sb.append("<tr><td>").append(esc(pname.getOrDefault(r.projectId(), ""))).append("</td><td>").append(esc(r.personName()))
              .append("</td><td>").append(esc(r.role() == null ? "" : r.role())).append("</td>");
            for (int i = 0; i < mm.length; i++) { sb.append("<td>").append(fmt(mm[i])).append("</td>"); colSum[i] += mm[i]; t += mm[i]; }
            sb.append("<td><strong>").append(fmt(t)).append("</strong></td></tr>");
            all += t;
        }
        sb.append("<tr><td><strong>합계</strong></td><td></td><td></td>");
        for (double c : colSum) sb.append("<td><strong>").append(fmt(c)).append("</strong></td>");
        sb.append("<td><strong>").append(fmt(all)).append("</strong></td></tr></tbody></table>");
        return sb.toString();
    }

    static double[] monthly(LocalDate start, int weeks, Map<Integer, BigDecimal> cells, List<YearMonth> months) {
        double[] out = new double[months.size()];
        for (Map.Entry<Integer, BigDecimal> e : cells.entrySet()) {
            if (e.getKey() < 0 || e.getKey() >= weeks) continue;
            int i = months.indexOf(YearMonth.from(start.plusWeeks(e.getKey())));
            if (i >= 0) out[i] += e.getValue().doubleValue();
        }
        for (int i = 0; i < out.length; i++) out[i] = Math.round(out[i] / ManMonthExcel.mondays(months.get(i)) * 100) / 100.0;
        return out;
    }

    private static String fmt(double v) {
        if (v == 0) return "";
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
