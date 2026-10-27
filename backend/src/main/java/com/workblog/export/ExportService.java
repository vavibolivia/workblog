package com.workblog.export;

import com.workblog.attachment.AttachmentDtos.AttachmentResponse;
import com.workblog.attachment.AttachmentService;
import com.workblog.post.Post;
import com.workblog.post.PostDtos.PostDetail;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 글 내보내기. HTML(단독 파일, 화면과 같은 모양), Markdown, Word(.docx). PDF 는 브라우저 인쇄로(화면의 인쇄 CSS).
 * 마크다운 글은 먼저 HTML 로 바꿔 같은 경로를 탄다.
 */
@Service
public class ExportService {
    public enum Format { HTML, MARKDOWN, DOCX }
    public record Exported(byte[] bytes, String filename, String contentType) {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final Pattern FILE_ID = Pattern.compile("/api/files/([0-9a-f-]{36})");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private final AttachmentService attachments;

    public ExportService(AttachmentService attachments) {
        this.attachments = attachments;
    }

    public Exported export(PostDetail post, Format format, boolean includeAttachments, String baseUrl) throws IOException {
        String html = post.contentFormat() == Post.ContentFormat.MARKDOWN ? markdownToHtml(post.content()) : post.content();
        String base = safeName(post.title());
        return switch (format) {
            case HTML -> new Exported(standaloneHtml(post, html, includeAttachments, baseUrl).getBytes(java.nio.charset.StandardCharsets.UTF_8), base + ".html", "text/html;charset=UTF-8");
            case MARKDOWN -> new Exported(markdown(post, html, includeAttachments, baseUrl).getBytes(java.nio.charset.StandardCharsets.UTF_8), base + ".md", "text/markdown;charset=UTF-8");
            case DOCX -> new Exported(HtmlToDocx.convert(post.title(), meta(post), withAttachmentList(html, post, includeAttachments, baseUrl), this::imageBytes),
                    base + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        };
    }

    // ---------------------------------------------------------------- HTML

    private String standaloneHtml(PostDetail p, String body, boolean attach, String baseUrl) {
        Document doc = Jsoup.parseBodyFragment(absolutize(body, baseUrl));
        doc.outputSettings().prettyPrint(false);
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n<meta charset=\"UTF-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(esc(p.title())).append("</title>\n");
        sb.append("<meta name=\"author\" content=\"").append(esc(p.author().displayName())).append("\">\n");
        sb.append("<meta name=\"generator\" content=\"WorkBlog\">\n<style>\n").append(CSS).append("</style>\n</head>\n<body>\n<article class=\"post\">\n");
        sb.append("<header>\n<div class=\"cat\">").append(esc(String.join(" › ", p.category().path()))).append("</div>\n");
        sb.append("<h1>").append(esc(p.title())).append("</h1>\n<div class=\"meta\">");
        sb.append(String.join(" · ", meta(p).stream().map(ExportService::esc).toList())).append("</div>\n");
        if (p.summary() != null && !p.summary().isBlank()) sb.append("<p class=\"summary\">").append(esc(p.summary())).append("</p>\n");
        sb.append("</header>\n<div class=\"rich\">\n").append(doc.body().html()).append("\n</div>\n");
        if (attach && !p.attachments().isEmpty()) {
            sb.append("<section class=\"attach\"><h2>첨부파일</h2><ul>\n");
            for (AttachmentResponse a : p.attachments()) {
                sb.append("<li><a href=\"").append(esc(abs(a.url(), baseUrl))).append("?download=true\">").append(esc(a.name())).append("</a> <span>").append(size(a.size())).append("</span></li>\n");
            }
            sb.append("</ul></section>\n");
        }
        if (!p.tags().isEmpty()) {
            sb.append("<div class=\"tags\">");
            for (String t : p.tags()) sb.append("<span>#").append(esc(t)).append("</span> ");
            sb.append("</div>\n");
        }
        sb.append("<footer>WorkBlog · ").append(esc(abs("/posts/" + p.id(), baseUrl))).append("</footer>\n</article>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static final String CSS = """
            :root { color-scheme: light; }
            body { margin: 0; background: #f4f6f9; font-family: Pretendard, 'Malgun Gothic', '맑은 고딕', 'Apple SD Gothic Neo', sans-serif; color: #1b2430; }
            .post { max-width: 860px; margin: 32px auto; background: #fff; padding: 44px 52px; border: 1px solid #e3e8ef; border-radius: 10px; }
            header { border-bottom: 1px solid #e3e8ef; padding-bottom: 18px; margin-bottom: 24px; }
            .cat { color: #2F9BE3; font-size: 13px; font-weight: 600; }
            h1 { margin: 6px 0 10px; font-size: 30px; line-height: 1.3; color: #0B2A5B; }
            .meta { color: #6b7686; font-size: 13.5px; }
            .summary { margin: 14px 0 0; padding: 12px 16px; background: #f4f6f9; border-radius: 8px; color: #3c4858; }
            .rich { font-size: 16px; line-height: 1.8; word-break: break-word; }
            .rich p { margin: 0 0 .5em; min-height: 1.4em; }
            .rich h1, .rich h2, .rich h3 { margin: 1.3em 0 .5em; line-height: 1.3; }
            .rich h1 { font-size: 28px; } .rich h2 { font-size: 23px; } .rich h3 { font-size: 19px; }
            .rich ul, .rich ol { padding-left: 1.6em; margin: 0 0 .8em; }
            .rich li > p { margin: 0; }
            .rich blockquote { margin: .6em 0 1em; padding: 10px 18px; border-left: 4px solid #2F9BE3; background: #eaf5fd; color: #0B2A5B; border-radius: 0 6px 6px 0; }
            .rich pre { margin: 0 0 1.2em; padding: 14px 16px; background: #0f1b2d; color: #e6edf7; border-radius: 8px; overflow: auto; font-size: 13.5px; line-height: 1.6; font-family: Consolas, 'D2Coding', monospace; }
            .rich code { font-family: Consolas, 'D2Coding', monospace; font-size: .92em; background: #eef1f5; padding: 2px 5px; border-radius: 4px; }
            .rich pre code { background: transparent; padding: 0; font-size: inherit; }
            .rich hr { border: 0; border-top: 2px solid #e3e8ef; margin: 1.6em 0; }
            .rich a { color: #2F9BE3; text-decoration: underline; text-underline-offset: 2px; }
            .rich mark { background: #fff2a8; }
            .rich table { border-collapse: collapse; margin: 0 0 1.2em; width: 100%; table-layout: fixed; font-size: 14.5px; }
            .rich th, .rich td { border: 1px solid #c9d2de; padding: 6px 10px; vertical-align: top; }
            .rich th { background: #f4f6f9; font-weight: 700; text-align: left; }
            .rich td > p, .rich th > p { margin: 0; }
            .rich figure, .rich .img-figure { margin: .4em 0 1em; display: flex; flex-direction: column; align-items: center; }
            .rich figure[data-align="left"] { align-items: flex-start; } .rich figure[data-align="right"] { align-items: flex-end; }
            .rich figure img { max-width: 100%; height: auto; border-radius: 6px; display: block; }
            .rich figcaption { font-size: 13px; color: #6b7686; text-align: center; margin-top: 6px; }
            .rich img { max-width: 100%; height: auto; }
            .attach { margin-top: 28px; padding-top: 16px; border-top: 1px solid #e3e8ef; }
            .attach h2 { font-size: 15px; margin: 0 0 8px; } .attach ul { margin: 0; padding-left: 1.2em; } .attach span { color: #6b7686; font-size: 12.5px; margin-left: 6px; }
            .tags { margin-top: 20px; } .tags span { display: inline-block; background: #eaf5fd; color: #0B2A5B; border-radius: 999px; padding: 3px 10px; font-size: 13px; margin: 0 4px 4px 0; }
            footer { margin-top: 28px; color: #9aa4b2; font-size: 12px; }
            @media print { body { background: #fff; } .post { border: 0; margin: 0; padding: 0; max-width: none; } }
            """;

    // ---------------------------------------------------------------- Markdown

    private String markdown(PostDetail p, String html, boolean attach, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(p.title()).append("\n\n");
        sb.append("> ").append(String.join(" · ", meta(p))).append("  \n> 분류: ").append(String.join(" › ", p.category().path()));
        if (!p.tags().isEmpty()) sb.append("  \n> 태그: ").append(String.join(", ", p.tags().stream().map(t -> "#" + t).toList()));
        sb.append("\n\n");
        if (p.summary() != null && !p.summary().isBlank()) sb.append("**").append(p.summary().trim()).append("**\n\n");
        String body = p.contentFormat() == Post.ContentFormat.MARKDOWN ? p.content()
                : FlexmarkHtmlConverter.builder().build().convert(prepareForMarkdown(absolutize(html, baseUrl)));
        sb.append(body.strip()).append("\n");
        if (attach && !p.attachments().isEmpty()) {
            sb.append("\n## 첨부파일\n\n");
            for (AttachmentResponse a : p.attachments()) sb.append("- [").append(a.name()).append("](").append(abs(a.url(), baseUrl)).append("?download=true) ").append(size(a.size())).append("\n");
        }
        sb.append("\n---\nWorkBlog · ").append(abs("/posts/" + p.id(), baseUrl)).append("\n");
        return sb.toString();
    }

    /** 표 셀 안의 <p> 는 마크다운 표에서 깨지므로 글자만 남기고, 사진 블록은 img 로 평탄화 */
    private static String prepareForMarkdown(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        for (Element cell : doc.select("td, th")) {
            for (Element p : cell.select("p")) p.unwrap();
            for (Element br : cell.select("br")) br.replaceWith(new org.jsoup.nodes.TextNode(" "));
        }
        for (Element fig : doc.select("figure")) {
            Element img = fig.selectFirst("img");
            Element cap = fig.selectFirst("figcaption");
            if (img == null) { fig.remove(); continue; }
            if (cap != null && !cap.text().isBlank()) img.attr("alt", cap.text());
            Element p = new Element("p").appendChild(img.clone());
            fig.replaceWith(p);
        }
        return doc.body().html();
    }

    // ---------------------------------------------------------------- 공통

    static List<String> meta(PostDetail p) {
        List<String> m = new ArrayList<>();
        m.add(p.author().displayName() + (p.author().department() == null ? "" : " · " + p.author().department()));
        m.add("작성 " + DT.format(p.publishedAt() != null ? p.publishedAt() : p.createdAt()));
        if (p.version() > 1) m.add("수정 " + DT.format(p.updatedAt()) + " (v" + p.version() + ")");
        return m;
    }

    private String withAttachmentList(String html, PostDetail p, boolean attach, String baseUrl) {
        if (!attach || p.attachments().isEmpty()) return html;
        StringBuilder sb = new StringBuilder(html);
        sb.append("<hr><h3>첨부파일</h3><ul>");
        for (AttachmentResponse a : p.attachments()) sb.append("<li><a href=\"").append(esc(abs(a.url(), baseUrl))).append("?download=true\">").append(esc(a.name())).append("</a> (").append(size(a.size())).append(")</li>");
        return sb.append("</ul>").toString();
    }

    /** 사진 바이트: 우리 저장소(/api/files/..)는 디스크에서, 외부 http(s) 는 받아온다(5초, 20MB). */
    private byte[] imageBytes(String src) {
        var m = FILE_ID.matcher(src);
        if (m.find()) return attachments.readBytes(m.group(1));
        if (src.startsWith("http://") || src.startsWith("https://")) {
            try {
                HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(src)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                return r.statusCode() == 200 && r.body().length <= 20 * 1024 * 1024 ? r.body() : null;
            } catch (Exception e) { return null; }
        }
        return null;
    }

    private static String absolutize(String html, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        for (Element e : doc.select("[src^=/]")) e.attr("src", abs(e.attr("src"), baseUrl));
        for (Element e : doc.select("[href^=/]")) e.attr("href", abs(e.attr("href"), baseUrl));
        return doc.body().html();
    }

    private static String abs(String path, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || !path.startsWith("/")) return path;
        return baseUrl.replaceAll("/+$", "") + path;
    }

    static String markdownToHtml(String md) {
        var exts = List.of(TablesExtension.create(), StrikethroughExtension.create());
        return HtmlRenderer.builder().extensions(exts).build().render(Parser.builder().extensions(exts).build().parse(md == null ? "" : md));
    }

    static String safeName(String title) {
        String n = (title == null ? "글" : title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) n = "글";
        return n.length() > 80 ? n.substring(0, 80).trim() : n;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
