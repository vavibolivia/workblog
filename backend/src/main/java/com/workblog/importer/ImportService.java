package com.workblog.importer;

import com.workblog.attachment.Attachment;
import com.workblog.attachment.AttachmentService;
import com.workblog.common.ApiException;
import com.workblog.post.HtmlSanitizer;
import com.workblog.user.User;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zwobble.mammoth.DocumentConverter;
import org.zwobble.mammoth.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 파일 → 글 본문(HTML) 변환. 지원: HTML(.html/.htm), Word(.docx), Excel(.xlsx/.xlsm/.xls), CSV, Markdown(.md), 텍스트(.txt).
 * 문서 안의 사진(워드 내장 이미지, HTML 의 data: 이미지)은 첨부로 저장하고 본문의 주소를 바꾼다.
 * 결과는 항상 {@link HtmlSanitizer#clean} 을 거친다.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    public enum SourceType { HTML, WORD, EXCEL, CSV, MARKDOWN, TEXT }

    /** 변환 결과. attachments = 변환 중 저장한 사진(+원본 파일) 의 publicId. */
    public record Converted(String title, String html, SourceType sourceType, List<Attachment> attachments, List<String> warnings) {}

    private static final Map<String, SourceType> BY_EXT = Map.ofEntries(
            Map.entry("html", SourceType.HTML), Map.entry("htm", SourceType.HTML),
            Map.entry("docx", SourceType.WORD),
            Map.entry("xlsx", SourceType.EXCEL), Map.entry("xlsm", SourceType.EXCEL), Map.entry("xls", SourceType.EXCEL),
            Map.entry("csv", SourceType.CSV),
            Map.entry("md", SourceType.MARKDOWN), Map.entry("markdown", SourceType.MARKDOWN),
            Map.entry("txt", SourceType.TEXT));

    private final AttachmentService attachments;

    public ImportService(AttachmentService attachments) {
        this.attachments = attachments;
    }

    public static Set<String> supportedExtensions() { return new TreeSet<>(BY_EXT.keySet()); }

    @Transactional
    public Converted convert(byte[] data, String filename, String contentType, boolean attachOriginal, User uploader) {
        if (data == null || data.length == 0) throw ApiException.badRequest("파일이 비어 있습니다.");
        String name = filename == null || filename.isBlank() ? "document" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if ("doc".equals(ext)) throw ApiException.badRequest("예전 워드 형식(.doc)은 가져올 수 없습니다. 워드에서 '다른 이름으로 저장 → Word 문서(*.docx)'로 바꿔 올려 주세요.");
        if ("hwp".equals(ext) || "hwpx".equals(ext)) throw ApiException.badRequest("한글(.hwp) 문서는 가져올 수 없습니다. 한글에서 워드(.docx)나 HTML 로 저장해 올려 주세요.");
        SourceType type = BY_EXT.get(ext);
        if (type == null) throw ApiException.badRequest("가져올 수 없는 형식입니다(." + ext + "). 지원 형식: " + String.join(", ", supportedExtensions()));

        String baseTitle = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        List<Attachment> saved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String title = null;
        String html;
        try {
            switch (type) {
                case HTML -> {
                    Document doc = Jsoup.parse(new ByteArrayInputStream(data), null, "");
                    title = blankToNull(doc.title());
                    Element body = doc.body();
                    if (title == null) title = firstHeading(body, true);
                    else removeLeadingHeading(body, title);
                    if (!doc.select("head style, link[rel=stylesheet]").isEmpty())
                        warnings.add("문서의 스타일시트(<style>)는 가져오지 않습니다. 글자 색·크기 등 태그에 직접 준 서식만 남습니다.");
                    extractDataImages(body, uploader, saved, warnings);
                    html = body.html();
                }
                case WORD -> {
                    DocumentConverter conv = new DocumentConverter()
                            .addStyleMap("u => u\nstrike => s\np[style-name='Quote'] => blockquote > p:fresh\np[style-name='인용'] => blockquote > p:fresh")
                            .imageConverter(image -> {
                                byte[] bytes;
                                try (var in = image.getInputStream()) { bytes = in.readAllBytes(); }
                                String ct = image.getContentType() == null ? "image/png" : image.getContentType();
                                Map<String, String> attrs = new HashMap<>();
                                if (ct.contains("svg") || ct.contains("emf") || ct.contains("wmf")) {
                                    warnings.add("워드의 벡터 그림(" + ct + ")은 가져오지 못했습니다.");
                                    attrs.put("alt", image.getAltText().orElse("그림"));
                                    return attrs;
                                }
                                Attachment a = attachments.storeBytes(bytes, "word-image-" + (saved.size() + 1) + "." + imageExt(ct), ct, uploader);
                                saved.add(a);
                                attrs.put("src", a.url());
                                image.getAltText().ifPresent(alt -> attrs.put("alt", alt));
                                return attrs;
                            });
                    Result<String> r = conv.convertToHtml(new ByteArrayInputStream(data));
                    for (String w : r.getWarnings()) {
                        if (warnings.size() < 10 && !w.startsWith("Unrecognised paragraph style") && !w.startsWith("Unrecognised run style")) warnings.add("워드: " + w);
                    }
                    Document doc = Jsoup.parseBodyFragment(r.getValue());
                    title = firstHeading(doc.body(), true);
                    html = doc.body().html();
                    warnings.add("워드의 글꼴·색·정렬 같은 꾸밈은 옮기지 않습니다. 제목·목록·표·굵게·기울임·밑줄·링크·사진은 옮깁니다.");
                }
                case EXCEL -> html = ExcelToHtml.convert(data, warnings);
                case CSV -> html = csvToTable(decodeText(data), baseTitle);
                case MARKDOWN -> {
                    String md = decodeText(data);
                    var exts = List.of(TablesExtension.create(), StrikethroughExtension.create());
                    String rendered = HtmlRenderer.builder().extensions(exts).build()
                            .render(Parser.builder().extensions(exts).build().parse(md));
                    Document doc = Jsoup.parseBodyFragment(rendered);
                    title = firstHeading(doc.body(), false);
                    html = doc.body().html();
                }
                case TEXT -> {
                    StringBuilder sb = new StringBuilder();
                    for (String para : decodeText(data).replace("\r\n", "\n").split("\n\\s*\n")) {
                        if (para.isBlank()) continue;
                        sb.append("<p>").append(ExcelToHtml.esc(para.strip()).replace("\n", "<br>")).append("</p>");
                    }
                    html = sb.toString();
                }
                default -> throw new IllegalStateException();
            }
        } catch (IOException e) {
            log.warn("가져오기 실패: {} ({})", name, e.getMessage());
            throw ApiException.badRequest("파일을 읽지 못했습니다: " + (e.getMessage() == null ? "형식이 올바르지 않습니다." : e.getMessage()));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // 손상된 파일 등 사용자 입력 문제라 스택은 남기지 않는다(로그 소음 방지)
            log.warn("가져오기 실패: {} ({})", name, e.toString());
            throw ApiException.badRequest("파일을 변환하지 못했습니다. 파일이 손상되었거나 지원하지 않는 내용이 있습니다.");
        }

        // 표는 에디터와 같은 모양으로
        Document out = Jsoup.parseBodyFragment(html);
        out.outputSettings().prettyPrint(false);
        for (Element t : out.select("table")) t.addClass("rich-table");
        for (Element a : out.select("a[href^=http]")) a.attr("target", "_blank");
        String cleaned = HtmlSanitizer.clean(out.body().html());
        if (HtmlSanitizer.toPlainText(cleaned).isBlank() && !cleaned.contains("<img") && !cleaned.contains("<table")) {
            throw ApiException.badRequest("파일에서 본문을 찾지 못했습니다.");
        }
        if (attachOriginal) {
            saved.add(attachments.storeOriginal(data, name, contentType, uploader));
        }
        return new Converted(title == null ? baseTitle : title, cleaned, type, saved, warnings);
    }

    /** HTML 의 data: 이미지 → 첨부. 상대 경로 이미지는 가져올 수 없어 지운다(경고). */
    private void extractDataImages(Element body, User uploader, List<Attachment> saved, List<String> warnings) {
        int dropped = 0;
        for (Element img : body.select("img")) {
            String src = img.attr("src").trim();
            if (src.startsWith("data:image/")) {
                int comma = src.indexOf(',');
                String meta = comma > 0 ? src.substring(5, comma) : "";
                if (comma < 0 || !meta.endsWith(";base64") || meta.contains("svg")) { img.remove(); dropped++; continue; }
                String ct = meta.substring(0, meta.indexOf(';'));
                try {
                    byte[] bytes = Base64.getMimeDecoder().decode(src.substring(comma + 1));
                    Attachment a = attachments.storeBytes(bytes, "html-image-" + (saved.size() + 1) + "." + imageExt(ct), ct, uploader);
                    saved.add(a);
                    img.attr("src", a.url());
                } catch (IllegalArgumentException e) { img.remove(); dropped++; }
            } else if (!(src.startsWith("http://") || src.startsWith("https://") || src.startsWith("/api/files/"))) {
                img.remove(); dropped++;
            }
        }
        if (dropped > 0) warnings.add("HTML 의 사진 " + dropped + "개는 파일 경로(상대 경로)라 가져오지 못했습니다. 사진은 에디터에서 다시 넣어 주세요.");
    }

    /** 첫 h1(없으면 h2)을 제목으로 쓰고 본문에서 뺀다. 문서 맨 앞에 있을 때만. */
    private static String firstHeading(Element body, boolean allowH2) {
        Element first = body.children().first();
        if (first == null) return null;
        if (first.tagName().equals("h1") || (allowH2 && first.tagName().equals("h2"))) {
            String t = blankToNull(first.text());
            if (t != null && t.length() <= 300) { first.remove(); return t; }
        }
        return null;
    }

    private static void removeLeadingHeading(Element body, String title) {
        Element first = body.children().first();
        if (first != null && first.tagName().matches("h[12]") && first.text().trim().equals(title.trim())) first.remove();
    }

    static String csvToTable(String text, String sheetName) {
        List<List<String>> rows = parseCsv(text);
        rows.removeIf(r -> r.stream().allMatch(String::isBlank));
        if (rows.isEmpty()) return "";
        int cols = Math.min(ExcelToHtml.MAX_COLS, rows.stream().mapToInt(List::size).max().orElse(0));
        StringBuilder t = new StringBuilder("<table class=\"rich-table\" data-sheet=\"").append(ExcelToHtml.esc(sheetName)).append("\"><tbody>");
        for (int i = 0; i < Math.min(rows.size(), ExcelToHtml.MAX_ROWS); i++) {
            String tag = i == 0 ? "th" : "td";
            t.append("<tr>");
            for (int c = 0; c < cols; c++) {
                String v = c < rows.get(i).size() ? rows.get(i).get(c) : "";
                t.append('<').append(tag).append("><p>").append(ExcelToHtml.esc(v.trim()).replace("\n", "<br>")).append("</p></").append(tag).append('>');
            }
            t.append("</tr>");
        }
        return t.append("</tbody></table>").toString();
    }

    /** RFC 4180 CSV(따옴표·줄바꿈 포함 칸). 구분자는 첫 줄에서 , ; 탭 중 많은 것. */
    static List<List<String>> parseCsv(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        char sep = ',';
        long commas = firstLine.chars().filter(ch -> ch == ',').count();
        long tabs = firstLine.chars().filter(ch -> ch == '\t').count();
        long semis = firstLine.chars().filter(ch -> ch == ';').count();
        if (tabs > commas && tabs >= semis) sep = '\t'; else if (semis > commas) sep = ';';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"' && cell.isEmpty()) quoted = true;
            else if (ch == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    /** UTF-8(BOM 포함) 우선, 깨지면 한국어 윈도우 기본(MS949)으로 읽는다. 엑셀이 저장한 CSV 대비. */
    static String decodeText(byte[] data) {
        int off = data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, off, data.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("MS949"));
        }
    }

    private static String imageExt(String ct) {
        return switch (ct == null ? "" : ct.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
