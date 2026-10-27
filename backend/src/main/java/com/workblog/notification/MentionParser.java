package com.workblog.notification;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 평문에서 @아이디 멘션을 찾는다. 아이디 규칙은 가입과 같다(영문·숫자·._-, 3~50자).
 * 이메일(a@b.com)처럼 앞에 글자가 붙은 @ 는 멘션이 아니다. 끝에 붙은 마침표·하이픈은 문장 부호로 보고 뺀다.
 */
public final class MentionParser {
    private MentionParser() {}

    public static final int MAX_MENTIONS = 20;
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9._@-])@([A-Za-z0-9._-]{3,60})");

    /** 소문자로 바꾼 아이디 집합(나온 순서, 최대 {@link #MAX_MENTIONS}개). */
    public static Set<String> parse(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.indexOf('@') < 0) return out;
        Matcher m = MENTION.matcher(text);
        while (m.find() && out.size() < MAX_MENTIONS) {
            String name = m.group(1).replaceAll("[.\\-]+$", "");
            if (name.length() >= 3 && name.length() <= 50) out.add(name.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
