package com.workblog.export;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 글 본문 HTML → Word(.docx). 에디터가 만드는 서식(글꼴·크기·색·배경색·굵게/기울임/밑줄/취소선·위/아래 첨자·정렬·들여쓰기),
 * 제목, 목록(중첩), 인용, 코드 블록, 구분선, 링크, 사진(정렬·크기·캡션), 표(머리글·배경색·병합)를 옮긴다.
 */
final class HtmlToDocx {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HtmlToDocx.class);
    private static final String BODY_FONT = "맑은 고딕";
    private static final String MONO_FONT = "Consolas";
    private static final int BODY_PT = 11;
    /** 본문 폭(A4, 기본 여백) ≈ 6.3in = 605px(96dpi) */
    private static final int CONTENT_PX = 605;
    private static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote",
            "pre", "table", "figure", "hr", "section", "article", "header", "footer", "main", "figcaption", "dl", "dt", "dd");

    private final XWPFDocument doc = new XWPFDocument();
    private final Function<String, byte[]> images;
    private int pictureSeq = 0;

    private HtmlToDocx(Function<String, byte[]> images) {
        this.images = images;
        CTFonts fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(BODY_FONT); fonts.setHAnsi(BODY_FONT); fonts.setEastAsia(BODY_FONT); fonts.setCs(BODY_FONT);
        doc.createStyles().setDefaultFonts(fonts);
    }

    /**
     * @param images 이미지 주소(src) → 바이트. 가져올 수 없으면 null(대체 텍스트로 남긴다).
     */
    static byte[] convert(String title, List<String> meta, String html, Function<String, byte[]> images) throws IOException {
        HtmlToDocx c = new HtmlToDocx(images);
        c.header(title, meta);
        c.blocks(Jsoup.parseBodyFragment(html == null ? "" : html).body(), new Sink.Doc(c.doc), Ctx.ROOT);
        c.doc.getProperties().getCoreProperties().setTitle(title);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            c.doc.write(out);
            c.doc.close();
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- sinks (문서 본문 / 표 칸)

    private sealed interface Sink permits Sink.Doc, Sink.Cell {
        XWPFParagraph paragraph();
        XWPFTable table(int rows, int cols);

        record Doc(XWPFDocument d) implements Sink {
            public XWPFParagraph paragraph() { return d.createParagraph(); }
            public XWPFTable table(int rows, int cols) { return d.createTable(rows, cols); }
        }

        /** 표 칸: 첫 문단은 칸이 이미 가진 빈 문단을 쓴다. 칸 안의 표는 지원하지 않는다(글자로 펼침). */
        final class Cell implements Sink {
            final XWPFTableCell cell;
            boolean firstUsed = false;
            Cell(XWPFTableCell cell) { this.cell = cell; }
            public XWPFParagraph paragraph() {
                if (!firstUsed && !cell.getParagraphs().isEmpty()) { firstUsed = true; return cell.getParagraphs().get(0); }
                firstUsed = true;
                return cell.addParagraph();
            }
            public XWPFTable table(int rows, int cols) { return null; }
        }
    }

    /** 블록 문맥: 들여쓰기(twip), 인용 여부, 목록 단계, 칸 정렬 */
    private record Ctx(int indentTwips, boolean quote, int listLevel, ParagraphAlignment align, boolean header) {
        static final Ctx ROOT = new Ctx(0, false, 0, null, false);
        Ctx indent(int t) { return new Ctx(indentTwips + t, quote, listLevel, align, header); }
        Ctx withQuote() { return new Ctx(indentTwips + 240, true, listLevel, align, header); }
        Ctx list() { return new Ctx(indentTwips, quote, listLevel + 1, align, header); }
        Ctx align(ParagraphAlignment a) { return new Ctx(indentTwips, quote, listLevel, a, header); }
        Ctx header(boolean h) { return new Ctx(indentTwips, quote, listLevel, align, h); }
    }

    /** 글자 서식 */
    private record Style(boolean bold, boolean italic, boolean underline, boolean strike, String color, String bg,
                         Double sizePt, String font, boolean mono, String vert, boolean mark) {
        static final Style BASE = new Style(false, false, false, false, null, null, null, null, false, null, false);
        Style withBold() { return new Style(true, italic, underline, strike, color, bg, sizePt, font, mono, vert, mark); }
        Style withItalic() { return new Style(bold, true, underline, strike, color, bg, sizePt, font, mono, vert, mark); }
        Style withUnderline() { return new Style(bold, italic, true, strike, color, bg, sizePt, font, mono, vert, mark); }
        Style withStrike() { return new Style(bold, italic, underline, true, color, bg, sizePt, font, mono, vert, mark); }
        Style color(String c) { return new Style(bold, italic, underline, strike, c, bg, sizePt, font, mono, vert, mark); }
        Style bg(String c) { return new Style(bold, italic, underline, strike, color, c, sizePt, font, mono, vert, mark); }
        Style size(Double s) { return new Style(bold, italic, underline, strike, color, bg, s, font, mono, vert, mark); }
        Style font(String f) { return new Style(bold, italic, underline, strike, color, bg, sizePt, f, mono, vert, mark); }
        Style withMono() { return new Style(bold, italic, underline, strike, color, bg, sizePt, font, true, vert, mark); }
        Style vert(String v) { return new Style(bold, italic, underline, strike, color, bg, sizePt, font, mono, v, mark); }
        Style withMark() { return new Style(bold, italic, underline, strike, color, bg, sizePt, font, mono, vert, true); }
    }

    // ---------------------------------------------------------------- 머리말

    private void header(String title, List<String> meta) {
        XWPFParagraph t = doc.createParagraph();
        t.setSpacingAfter(120);
        XWPFRun r = t.createRun();
        r.setText(title);
        r.setBold(true);
        r.setFontSize(20);
        r.setColor("0B2A5B");
        if (meta != null && !meta.isEmpty()) {
            XWPFParagraph m = doc.createParagraph();
            m.setSpacingAfter(240);
            border(m, false, "C9D2DE");
            for (int i = 0; i < meta.size(); i++) {
                XWPFRun mr = m.createRun();
                mr.setText(meta.get(i));
                mr.setFontSize(9);
                mr.setColor("6B7686");
                if (i < meta.size() - 1) mr.addBreak();
            }
        }
    }

    // ---------------------------------------------------------------- 블록

    /** 표 머리글 칸 안의 글자는 굵게 */
    private static Style base(Ctx ctx) { return ctx.header() ? Style.BASE.withBold() : Style.BASE; }

    private void blocks(Element parent, Sink sink, Ctx ctx) {
        XWPFParagraph pending = null;
        for (Node n : parent.childNodes()) {
            if (n instanceof Element el && isBlock(el)) {
                pending = null;
                block(el, sink, ctx);
            } else if (n instanceof Element el && el.tagName().equals("img")) {
                pending = null;
                picture(el, sink, ctx, null, null);
            } else {
                if (n instanceof TextNode tn && tn.isBlank() && pending == null) continue;
                if (n instanceof Element el && el.tagName().equals("br") && pending == null) { paragraph(sink, ctx, null); continue; }
                if (pending == null) pending = paragraph(sink, ctx, null);
                inline(pending, n, base(ctx), false);
            }
        }
    }

    private void block(Element el, Sink sink, Ctx ctx) {
        String tag = el.tagName();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                XWPFParagraph p = paragraph(sink, ctx, el);
                p.setSpacingBefore(level <= 2 ? 360 : 240);
                p.setSpacingAfter(120);
                outline(p, level - 1);
                double size = switch (level) { case 1 -> 18; case 2 -> 15; case 3 -> 13; default -> 12; };
                inlineChildren(p, el, Style.BASE.withBold().size(size), false);
            }
            case "p", "dt", "dd", "figcaption" -> {
                if (hasBlockChild(el)) { blocks(el, sink, ctx); return; }
                XWPFParagraph p = paragraph(sink, ctx, el);
                Style base = tag.equals("figcaption") ? Style.BASE.size(9.0).color("6B7686") : base(ctx);
                if (tag.equals("figcaption")) p.setAlignment(ParagraphAlignment.CENTER);
                inlineChildren(p, el, base, false);
            }
            case "div", "section", "article", "header", "footer", "main", "dl" -> {
                if (hasBlockChild(el)) blocks(el, sink, ctx.indent(indentOf(el)));
                else inlineChildren(paragraph(sink, ctx, el), el, base(ctx), false);
            }
            case "ul", "ol" -> list(el, sink, ctx.list(), tag.equals("ol"));
            case "li" -> list(new Element("ul").appendChild(el.clone()), sink, ctx.list(), false);
            case "blockquote" -> blocks(el, sink, ctx.withQuote());
            case "pre" -> {
                XWPFParagraph p = paragraph(sink, ctx, el);
                shade(p, "F1F4F8");
                String[] lines = el.wholeText().replace("\r", "").replaceAll("\n$", "").split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    XWPFRun r = p.createRun();
                    r.setText(lines[i].replace("\t", "    "));
                    r.setFontFamily(MONO_FONT);
                    r.setFontSize(9.5);
                    if (i < lines.length - 1) r.addBreak();
                }
            }
            case "hr" -> {
                XWPFParagraph p = paragraph(sink, ctx, el);
                border(p, false, "C9D2DE");
            }
            case "figure" -> {
                Element img = el.selectFirst("img");
                Element cap = el.selectFirst("figcaption");
                if (img != null) picture(img, sink, ctx, el.attr("data-align"), el.attr("data-width"));
                if (cap != null && !cap.text().isBlank()) block(cap, sink, ctx);
            }
            case "table" -> table(el, sink, ctx);
            default -> blocks(el, sink, ctx);
        }
    }

    private void list(Element listEl, Sink sink, Ctx ctx, boolean ordered) {
        int n = 1;
        if (ordered && listEl.hasAttr("start")) {
            try { n = Integer.parseInt(listEl.attr("start").trim()); } catch (NumberFormatException ignored) { }
        }
        int indent = ctx.indentTwips() + 360 * ctx.listLevel();
        for (Element li : listEl.children()) {
            if (!li.tagName().equals("li")) continue;
            String bullet = ordered ? (n++) + ". " : (ctx.listLevel() % 2 == 1 ? "• " : "◦ ");
            XWPFParagraph p = sink.paragraph();
            applyParagraph(p, ctx, li);
            p.setIndentationLeft(indent + 300);
            p.setIndentationHanging(300);
            p.setSpacingAfter(40);
            XWPFRun b = p.createRun();
            b.setText(bullet);
            boolean first = true;
            for (Node child : li.childNodes()) {
                if (child instanceof Element ce && (ce.tagName().equals("ul") || ce.tagName().equals("ol"))) {
                    list(ce, sink, ctx.list(), ce.tagName().equals("ol"));
                    first = false;
                } else if (child instanceof Element ce && isBlock(ce)) {
                    if (first) { inlineChildren(p, ce, Style.BASE, false); first = false; }
                    else {
                        XWPFParagraph more = sink.paragraph();
                        applyParagraph(more, ctx, ce);
                        more.setIndentationLeft(indent + 300);
                        inlineChildren(more, ce, Style.BASE, false);
                    }
                } else {
                    inline(p, child, Style.BASE, false);
                }
            }
        }
    }

    private void table(Element tableEl, Sink sink, Ctx ctx) {
        List<Element> rows = new ArrayList<>();
        for (Element r : tableEl.select("tr")) if (r.closest("table") == tableEl) rows.add(r);
        if (rows.isEmpty()) return;
        // 격자 배치: 병합 칸이 차지하는 자리를 건너뛰며 칸을 놓는다
        record Placed(int r, int c, int cs, int rs, Element el) {}
        List<Placed> placed = new ArrayList<>();
        Map<Integer, Set<Integer>> taken = new HashMap<>();
        int cols = 0;
        for (int r = 0; r < rows.size(); r++) {
            int c = 0;
            for (Element cell : rows.get(r).children()) {
                if (!cell.tagName().equals("td") && !cell.tagName().equals("th")) continue;
                while (taken.getOrDefault(r, Set.of()).contains(c)) c++;
                int cs = Math.max(1, Math.min(50, parseInt(cell.attr("colspan"), 1)));
                int rs = Math.max(1, Math.min(rows.size() - r, parseInt(cell.attr("rowspan"), 1)));
                placed.add(new Placed(r, c, cs, rs, cell));
                for (int dr = 0; dr < rs; dr++)
                    for (int dc = 0; dc < cs; dc++) taken.computeIfAbsent(r + dr, k -> new HashSet<>()).add(c + dc);
                c += cs;
                cols = Math.max(cols, c);
            }
        }
        cols = Math.max(cols, 1);
        XWPFTable t = sink.table(rows.size(), cols);
        if (t == null) { // 칸 안의 표: 글자로 펼친다
            for (Element r : rows) {
                XWPFParagraph p = sink.paragraph();
                XWPFRun run = p.createRun();
                run.setText(String.join(" | ", r.children().eachText()));
            }
            return;
        }
        t.setWidth("100%");
        if (ctx.indentTwips() > 0) t.setTableAlignment(TableRowAlign.LEFT);
        for (Placed pc : placed) {
            XWPFTableCell cell = t.getRow(pc.r()).getCell(pc.c());
            if (cell == null) continue;
            boolean th = pc.el().tagName().equals("th");
            Map<String, String> st = styles(pc.el());
            String bg = color(st.get("background-color"));
            if (bg != null) cell.setColor(bg);
            else if (th) cell.setColor("F3F5F8");
            cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.TOP);
            ParagraphAlignment a = alignment(st.get("text-align"));
            Sink.Cell cs = new Sink.Cell(cell);
            Ctx cellCtx = Ctx.ROOT.align(a).header(th);
            blocks(pc.el(), cs, cellCtx);
            if (!cs.firstUsed && !cell.getParagraphs().isEmpty()) applyParagraph(cell.getParagraphs().get(0), cellCtx, null);
            for (XWPFParagraph p : cell.getParagraphs()) p.setSpacingAfter(0);
            // 병합
            for (int dr = 0; dr < pc.rs(); dr++) {
                for (int dc = 0; dc < pc.cs(); dc++) {
                    XWPFTableCell target = t.getRow(pc.r() + dr).getCell(pc.c() + dc);
                    if (target == null) continue;
                    CTTcPr pr = target.getCTTc().isSetTcPr() ? target.getCTTc().getTcPr() : target.getCTTc().addNewTcPr();
                    if (pc.cs() > 1) pr.addNewHMerge().setVal(dc == 0 ? STMerge.RESTART : STMerge.CONTINUE);
                    if (pc.rs() > 1) pr.addNewVMerge().setVal(dr == 0 ? STMerge.RESTART : STMerge.CONTINUE);
                }
            }
        }
        // 표 다음에 빈 줄(표가 붙지 않게)
        if (sink instanceof Sink.Doc) sink.paragraph().setSpacingAfter(0);
    }

    // ---------------------------------------------------------------- 사진

    private void picture(Element img, Sink sink, Ctx ctx, String figAlign, String figWidth) {
        XWPFParagraph p = paragraph(sink, ctx, null);
        p.setAlignment(switch (figAlign == null ? "" : figAlign) {
            case "left" -> ParagraphAlignment.LEFT;
            case "right" -> ParagraphAlignment.RIGHT;
            default -> ParagraphAlignment.CENTER;
        });
        addPicture(p, img, figWidth);
    }

    private void addPicture(XWPFParagraph p, Element img, String widthHint) {
        String src = img.attr("src");
        String alt = img.attr("alt");
        byte[] data = src.isBlank() ? null : images.apply(src);
        BufferedImage bi = null;
        try { if (data != null) bi = ImageIO.read(new ByteArrayInputStream(data)); } catch (IOException ignored) { }
        int type = data == null ? -1 : pictureType(data);
        if (data == null || bi == null || type < 0) {
            XWPFRun r = p.createRun();
            r.setText("[사진" + (alt.isBlank() ? "" : ": " + alt) + "]");
            r.setColor("6B7686");
            return;
        }
        double widthPx = bi.getWidth();
        String w = widthHint != null && !widthHint.isBlank() ? widthHint : styles(img).getOrDefault("width", img.attr("width"));
        Matcher m = Pattern.compile("^([\\d.]+)\\s*(%|px)?$").matcher(w == null ? "" : w.trim());
        if (m.matches()) {
            double v = Double.parseDouble(m.group(1));
            widthPx = "%".equals(m.group(2)) ? CONTENT_PX * v / 100.0 : v;
        }
        widthPx = Math.min(widthPx, CONTENT_PX);
        double heightPx = widthPx * bi.getHeight() / Math.max(1, bi.getWidth());
        try {
            XWPFRun r = p.createRun();
            r.addPicture(new ByteArrayInputStream(data), type, "image" + (++pictureSeq), Units.pixelToEMU((int) Math.round(widthPx)), Units.pixelToEMU((int) Math.round(heightPx)));
        } catch (Exception e) {
            log.warn("Word 내보내기: 사진을 넣지 못했습니다 ({}): {}", src, e.toString());
            XWPFRun r = p.createRun();
            r.setText("[사진" + (alt.isBlank() ? "" : ": " + alt) + "]");
        }
    }

    private static int pictureType(byte[] d) {
        if (d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return Document.PICTURE_TYPE_PNG;
        if (d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return Document.PICTURE_TYPE_JPEG;
        if (d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return Document.PICTURE_TYPE_GIF;
        if (d.length > 2 && d[0] == 'B' && d[1] == 'M') return Document.PICTURE_TYPE_BMP;
        return -1;
    }

    // ---------------------------------------------------------------- 글자(인라인)

    private void inlineChildren(XWPFParagraph p, Element el, Style base, boolean pre) {
        Style s = styleOf(el, base);
        for (Node n : el.childNodes()) inline(p, n, s, pre);
    }

    private void inline(XWPFParagraph p, Node n, Style s, boolean pre) {
        if (n instanceof TextNode tn) {
            String text = pre ? tn.getWholeText() : tn.getWholeText().replaceAll("\\s+", " ");
            if (text.isEmpty()) return;
            if (p.getRuns().isEmpty() && !pre) text = text.stripLeading();
            if (text.isEmpty()) return;
            run(p, text, s);
            return;
        }
        if (!(n instanceof Element el)) return;
        switch (el.tagName()) {
            case "br" -> { XWPFRun r = p.getRuns().isEmpty() ? p.createRun() : p.getRuns().get(p.getRuns().size() - 1); r.addBreak(); }
            case "img" -> addPicture(p, el, null);
            case "a" -> {
                String href = el.attr("href");
                String text = el.text();
                if (text.isBlank()) text = href;
                if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                    XWPFHyperlinkRun hr = p.createHyperlinkRun(href);
                    hr.setText(text);
                    hr.setUnderline(UnderlinePatterns.SINGLE);
                    hr.setColor("1F6FB5");
                    applyRun(hr, styleOf(el, s).color(null).withUnderline());
                    hr.setColor("1F6FB5");
                } else {
                    for (Node c : el.childNodes()) inline(p, c, styleOf(el, s), pre);
                }
            }
            default -> {
                Style cs = styleOf(el, s);
                boolean childPre = pre || el.tagName().equals("pre");
                for (Node c : el.childNodes()) inline(p, c, cs, childPre);
            }
        }
    }

    private Style styleOf(Element el, Style base) {
        Style s = base;
        switch (el.tagName()) {
            case "b", "strong", "th" -> s = s.withBold();
            case "i", "em", "cite" -> s = s.withItalic();
            case "u", "ins" -> s = s.withUnderline();
            case "s", "del", "strike" -> s = s.withStrike();
            case "code", "kbd", "samp" -> s = s.withMono();
            case "sub" -> s = s.vert("sub");
            case "sup" -> s = s.vert("sup");
            case "mark" -> s = s.withMark();
            default -> { }
        }
        Map<String, String> st = styles(el);
        String c = color(st.get("color"));
        if (c != null) s = s.color(c);
        String bg = color(st.get("background-color"));
        if (bg != null && !el.tagName().equals("td") && !el.tagName().equals("th")) s = s.bg(bg);
        Double size = fontSizePt(st.get("font-size"));
        if (size != null) s = s.size(size);
        String ff = st.get("font-family");
        if (ff != null && !ff.isBlank()) s = s.font(ff.split(",")[0].replace("\"", "").replace("'", "").trim());
        String fw = st.get("font-weight");
        if (fw != null && (fw.equals("bold") || fw.matches("[6-9]00"))) s = s.withBold();
        if ("italic".equals(st.get("font-style"))) s = s.withItalic();
        String td = st.getOrDefault("text-decoration", "");
        if (td.contains("underline")) s = s.withUnderline();
        if (td.contains("line-through")) s = s.withStrike();
        return s;
    }

    private void run(XWPFParagraph p, String text, Style s) {
        XWPFRun r = p.createRun();
        r.setText(text);
        applyRun(r, s);
    }

    private void applyRun(XWPFRun r, Style s) {
        if (s.bold()) r.setBold(true);
        if (s.italic()) r.setItalic(true);
        if (s.underline()) r.setUnderline(UnderlinePatterns.SINGLE);
        if (s.strike()) r.setStrikeThrough(true);
        if (s.color() != null) r.setColor(s.color());
        if (s.sizePt() != null) r.setFontSize(s.sizePt());
        else if (s.mono()) r.setFontSize(BODY_PT - 1);
        else r.setFontSize(BODY_PT);
        if (s.mono()) r.setFontFamily(MONO_FONT);
        else if (s.font() != null) { r.setFontFamily(s.font()); r.setFontFamily(s.font(), XWPFRun.FontCharRange.eastAsia); }
        if ("sub".equals(s.vert())) r.setSubscript(VerticalAlign.SUBSCRIPT);
        if ("sup".equals(s.vert())) r.setSubscript(VerticalAlign.SUPERSCRIPT);
        if (s.mark() && s.bg() == null) r.setTextHighlightColor("yellow");
        if (s.bg() != null || s.mono()) {
            CTRPr pr = r.getCTR().isSetRPr() ? r.getCTR().getRPr() : r.getCTR().addNewRPr();
            CTShd shd = pr.addNewShd();
            shd.setVal(STShd.CLEAR);
            shd.setColor("auto");
            shd.setFill(s.bg() != null ? s.bg() : "EEF1F5");
        }
    }

    // ---------------------------------------------------------------- 문단 공통

    private XWPFParagraph paragraph(Sink sink, Ctx ctx, Element el) {
        XWPFParagraph p = sink.paragraph();
        applyParagraph(p, ctx, el);
        return p;
    }

    private void applyParagraph(XWPFParagraph p, Ctx ctx, Element el) {
        p.setSpacingAfter(ctx.header() ? 0 : 100);
        p.setSpacingBetween(1.3, LineSpacingRule.AUTO);
        int indent = ctx.indentTwips() + (el == null ? 0 : indentOf(el));
        if (indent > 0) p.setIndentationLeft(indent);
        ParagraphAlignment a = el == null ? null : alignment(styles(el).get("text-align"));
        if (a == null) a = ctx.align();
        if (a != null) p.setAlignment(a);
        if (ctx.quote()) {
            border(p, true, "2F9BE3");
            shade(p, "EAF5FD");
        }
    }

    private static int indentOf(Element el) {
        int px = 0;
        if (el.hasAttr("data-indent")) px = parseInt(el.attr("data-indent"), 0) * 32;
        Map<String, String> st = styles(el);
        Integer ml = pxValue(st.get("margin-left"));
        Integer pl = pxValue(st.get("padding-left"));
        if (ml != null) px = Math.max(px, ml);
        if (pl != null) px += pl;
        return Math.max(0, Math.min(px, 400)) * 15; // 1px = 15 twip
    }

    private static void outline(XWPFParagraph p, int level) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        ppr.addNewOutlineLvl().setVal(BigInteger.valueOf(level));
    }

    private static void border(XWPFParagraph p, boolean left, String color) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTPBdr bdr = ppr.isSetPBdr() ? ppr.getPBdr() : ppr.addNewPBdr();
        CTBorder b = left ? bdr.addNewLeft() : bdr.addNewBottom();
        b.setVal(STBorder.SINGLE);
        b.setSz(BigInteger.valueOf(left ? 24 : 6));
        b.setSpace(BigInteger.valueOf(left ? 8 : 4));
        b.setColor(color);
    }

    private static void shade(XWPFParagraph p, String fill) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTShd shd = ppr.isSetShd() ? ppr.getShd() : ppr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(fill);
    }

    // ---------------------------------------------------------------- 도우미

    private static boolean isBlock(Element el) { return BLOCKS.contains(el.tagName()); }

    private static boolean hasBlockChild(Element el) {
        for (Element c : el.children()) if (isBlock(c) || c.tagName().equals("table")) return true;
        return false;
    }

    static Map<String, String> styles(Element el) {
        Map<String, String> m = new HashMap<>();
        for (String decl : el.attr("style").split(";")) {
            int i = decl.indexOf(':');
            if (i > 0) m.put(decl.substring(0, i).trim().toLowerCase(Locale.ROOT), decl.substring(i + 1).trim());
        }
        return m;
    }

    private static ParagraphAlignment alignment(String v) {
        if (v == null) return null;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "center" -> ParagraphAlignment.CENTER;
            case "right" -> ParagraphAlignment.RIGHT;
            case "justify" -> ParagraphAlignment.BOTH;
            case "left" -> ParagraphAlignment.LEFT;
            default -> null;
        };
    }

    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");
    private static final Map<String, String> NAMED = Map.of("red", "FF0000", "blue", "0000FF", "green", "008000", "black", "000000",
            "white", "FFFFFF", "gray", "808080", "grey", "808080", "yellow", "FFFF00", "orange", "FFA500", "navy", "000080");

    /** CSS 색 → RRGGBB. 모르면 null. */
    static String color(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) {
            String h = s.substring(1);
            if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            return h.matches("[0-9a-f]{6}") ? h.toUpperCase(Locale.ROOT) : null;
        }
        Matcher m = RGB.matcher(s);
        if (m.find()) return String.format("%02X%02X%02X", clamp(m.group(1)), clamp(m.group(2)), clamp(m.group(3)));
        return NAMED.get(s);
    }

    private static int clamp(String n) { return Math.max(0, Math.min(255, Integer.parseInt(n))); }

    /** px/pt/em → pt */
    static Double fontSizePt(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("^([\\d.]+)\\s*(px|pt|em|rem)?$").matcher(v.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        double n = Double.parseDouble(m.group(1));
        double pt = switch (m.group(2) == null ? "px" : m.group(2)) {
            case "pt" -> n;
            case "em", "rem" -> n * BODY_PT;
            default -> n * 0.75;
        };
        return Math.max(6, Math.min(72, Math.round(pt * 2) / 2.0));
    }

    private static Integer pxValue(String v) {
        if (v == null) return null;
        Matcher m = Pattern.compile("^([\\d.]+)\\s*px$").matcher(v.trim());
        return m.matches() ? (int) Math.round(Double.parseDouble(m.group(1))) : null;
    }

    private static int parseInt(String s, int dflt) {
        try { return s == null || s.isBlank() ? dflt : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
