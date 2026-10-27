package com.workblog.importer;

import com.workblog.post.HtmlSanitizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.jsoup.Jsoup;
import org.zwobble.mammoth.DocumentConverter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 첨부 파일 → 검색용 평문. 가져오기 변환기를 그대로 쓴다(엑셀·CSV → 표 HTML → 평문, 워드 → 원문 텍스트).
 * 지원: xlsx/xlsm/xls, csv, docx, txt, md/markdown, html/htm, pdf. 그 밖의 형식은 null.
 * 결과는 공백을 줄이고 NUL 을 지운 뒤 UTF-8 기준 {@link #MAX_BYTES} 이하로 자른다.
 */
public final class TextExtractor {
    private TextExtractor() {}

    public static final int MAX_BYTES = 200 * 1024;
    public static final Set<String> EXTENSIONS = Set.of("xlsx", "xlsm", "xls", "csv", "docx", "txt", "md", "markdown", "html", "htm", "pdf");

    public static boolean supports(String filename) {
        return EXTENSIONS.contains(extension(filename));
    }

    /** 추출한 평문(비어 있으면 ""). 지원하지 않는 형식이면 null. 파일이 손상되었으면 IOException/RuntimeException. */
    public static String extract(byte[] data, String filename) throws IOException {
        String ext = extension(filename);
        if (!EXTENSIONS.contains(ext)) return null;
        if (data == null || data.length == 0) return "";
        String text = switch (ext) {
            case "xlsx", "xlsm", "xls" -> HtmlSanitizer.toPlainText(ExcelToHtml.convert(data, new ArrayList<>()));
            case "csv" -> HtmlSanitizer.toPlainText(ImportService.csvToTable(ImportService.decodeText(data), "csv"));
            case "docx" -> new DocumentConverter().extractRawText(new ByteArrayInputStream(data)).getValue();
            case "txt" -> ImportService.decodeText(data);
            case "md", "markdown" -> {
                var exts = List.of(TablesExtension.create());
                yield TextContentRenderer.builder().build().render(Parser.builder().extensions(exts).build().parse(ImportService.decodeText(data)));
            }
            case "html", "htm" -> Jsoup.parse(new String(data, StandardCharsets.UTF_8)).text();
            case "pdf" -> {
                try (PDDocument doc = Loader.loadPDF(data)) {
                    yield new PDFTextStripper().getText(doc);
                }
            }
            default -> null;
        };
        return normalize(text);
    }

    /** NUL 제거(PostgreSQL text 가 거부), 공백 정리, 200KB 로 자르기. */
    static String normalize(String text) {
        if (text == null) return "";
        String t = text.replace("\u0000", "").replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
        return truncateUtf8(t, MAX_BYTES);
    }

    /** UTF-8 로 maxBytes 를 넘지 않게 자른다(서로게이트 쌍은 쪼개지 않는다). */
    static String truncateUtf8(String s, int maxBytes) {
        if (s.length() * 3L <= maxBytes) return s;
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > maxBytes) return s.substring(0, i);
            bytes += n;
            i += Character.charCount(cp);
        }
        return s;
    }

    static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
