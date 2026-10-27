package com.workblog.ops;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * WARN 이상 로그를 메모리 링 버퍼(최근 200건)에 보관하는 logback appender. logback-spring.xml 에서 등록한다.
 * logback 이 리플렉션으로 만들기 때문에 Spring 빈이 아니며, 상태는 static 으로 둔다(앱에 appender 인스턴스는 하나).
 */
public class ErrorLogBuffer extends AppenderBase<ILoggingEvent> {
    public static final int CAPACITY = 200;

    /** 상태 API 에 그대로 내보내는 한 줄. exception 은 예외 클래스와 메시지(첫 줄)만. */
    public record Entry(Instant time, String level, String logger, String message, String exception) {}

    private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<>(CAPACITY);

    @Override
    protected void append(ILoggingEvent event) {
        String ex = null;
        IThrowableProxy t = event.getThrowableProxy();
        if (t != null) ex = t.getMessage() == null ? t.getClassName() : t.getClassName() + ": " + t.getMessage();
        add(new Entry(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
                shortLogger(event.getLoggerName()), event.getFormattedMessage(), ex));
    }

    static void add(Entry e) {
        synchronized (BUFFER) {
            if (BUFFER.size() >= CAPACITY) BUFFER.pollFirst();
            BUFFER.addLast(e);
        }
    }

    /** 최신순으로 최대 n 건. */
    public static List<Entry> recent(int n) {
        synchronized (BUFFER) {
            List<Entry> out = new ArrayList<>(Math.min(n, BUFFER.size()));
            Iterator<Entry> it = BUFFER.descendingIterator();
            while (it.hasNext() && out.size() < n) out.add(it.next());
            return out;
        }
    }

    /** since 이후(포함)에 쌓인 건수 — 경고 판정용. */
    public static int countSince(Instant since) {
        synchronized (BUFFER) {
            int c = 0;
            Iterator<Entry> it = BUFFER.descendingIterator();
            while (it.hasNext()) {
                if (it.next().time().isBefore(since)) break;
                c++;
            }
            return c;
        }
    }

    static void clear() {
        synchronized (BUFFER) { BUFFER.clear(); }
    }

    /** com.workblog.post.PostService → PostService. 화면 표에 짧게 보이도록. */
    static String shortLogger(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }
}
