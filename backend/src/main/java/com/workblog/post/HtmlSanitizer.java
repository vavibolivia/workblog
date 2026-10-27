package com.workblog.post;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 리치 텍스트 본문 정화. 에디터가 만드는 태그·속성(글꼴·크기·색·정렬·들여쓰기·표·이미지 등)만 남기고
 * 스크립트·이벤트 핸들러·외부 스타일은 지운다. 저장 시 한 번, 화면에서도 DOMPurify 로 한 번 더 거른다.
 */
public final class HtmlSanitizer {
    private HtmlSanitizer() {}

    private static final Set<String> STYLE_PROPS = Set.of(
            "color", "background-color", "font-family", "font-size", "font-weight", "font-style",
            "text-align", "text-decoration", "line-height", "margin-left", "padding-left", "text-indent",
            "width", "height", "max-width", "vertical-align", "border", "border-collapse", "min-width");
    private static final Pattern STYLE_VALUE_OK = Pattern.compile("^[\\w\\s#%,.()'\"\\-/]+$");
    private static final Pattern URL_IN_VALUE = Pattern.compile("url\\s*\\(", Pattern.CASE_INSENSITIVE);

    public static String clean(String html) {
        if (html == null || html.isBlank()) return "";
        Safelist sl = Safelist.relaxed()
                .addTags("figure", "figcaption", "span", "u", "s", "del", "mark", "hr", "sub", "sup", "code", "pre", "blockquote",
                         "table", "thead", "tbody", "tr", "th", "td", "colgroup", "col")
                .addAttributes(":all", "style", "class")
                .addAttributes("a", "href", "target", "rel", "title")
                .addAttributes("img", "src", "alt", "title", "width", "height", "data-align", "data-width")
                .addAttributes("figure", "data-align", "data-width", "data-diagram")
                .addAttributes("table", "data-sheet")
                .addAttributes("td", "colspan", "rowspan", "colwidth")
                .addAttributes("th", "colspan", "rowspan", "colwidth")
                .addAttributes("p", "data-indent")
                .addAttributes("h1", "data-indent").addAttributes("h2", "data-indent").addAttributes("h3", "data-indent")
                .addAttributes("li", "data-indent")
                .addAttributes("pre", "data-language")
                .addProtocols("a", "href", "http", "https", "mailto")
                .addProtocols("img", "src", "http", "https")
                .removeProtocols("img", "src", "http", "https"); // 상대 경로(/api/files/..)만 허용하려고 아래에서 직접 검사
        sl.preserveRelativeLinks(true);
        // baseUri 가 있어야 preserveRelativeLinks 가 동작해 /posts/22 같은 사내 상대 링크가 살아남는다
        Document doc = Jsoup.parseBodyFragment(html, "https://workblog.local/");
        doc.outputSettings().prettyPrint(false);
        // img src: 우리 저장소(/api/files/) 또는 http(s) 만
        for (Element img : doc.select("img")) {
            String src = img.attr("src").trim();
            if (!(src.startsWith("/api/files/") || src.startsWith("http://") || src.startsWith("https://"))) img.remove();
        }
        for (Element a : doc.select("a[target]")) a.attr("rel", "noopener noreferrer");
        Document cleaned = new org.jsoup.safety.Cleaner(sl).clean(doc);
        cleaned.outputSettings().prettyPrint(false);
        // style 속성은 허용 목록의 속성만, url() 금지
        for (Element el : cleaned.select("[style]")) {
            StringBuilder keep = new StringBuilder();
            for (String decl : el.attr("style").split(";")) {
                int i = decl.indexOf(':');
                if (i < 0) continue;
                String prop = decl.substring(0, i).trim().toLowerCase();
                String val = decl.substring(i + 1).trim();
                if (STYLE_PROPS.contains(prop) && STYLE_VALUE_OK.matcher(val).matches() && !URL_IN_VALUE.matcher(val).find()) {
                    keep.append(prop).append(':').append(val).append(';');
                }
            }
            if (keep.isEmpty()) el.removeAttr("style"); else el.attr("style", keep.toString());
        }
        return cleaned.body().html();
    }

    /** 검색 요약·비교용 평문. 블록 경계는 줄바꿈으로. */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select("br").append("\\n");
        doc.select("p, div, h1, h2, h3, h4, li, tr, blockquote, pre, figcaption, hr").append("\\n");
        return doc.text().replace("\\n", "\n").replaceAll("[ \\t]+", " ").trim();
    }
}
