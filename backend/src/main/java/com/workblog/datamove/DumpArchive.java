package com.workblog.datamove;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workblog.common.ApiException;
import com.workblog.datamove.DumpFormat.FileEntry;
import com.workblog.datamove.DumpFormat.Manifest;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 올라온 덤프 zip 을 열고 검사한다.
 * <ul>
 *   <li>항목 이름: 허용 형식만(경로 탈출 '..', 절대 경로, 역슬래시 등 거부). 파일은 이름 그대로 디스크에 풀지 않는다.</li>
 *   <li>과대 압축: 항목 수, 실제로 읽은 바이트(헤더 값을 믿지 않음) 기준 항목·전체 크기, 압축률.</li>
 *   <li>무결성: manifest 의 SHA-256·크기와 모든 항목을 대조. manifest 에 없는 항목도 거부.</li>
 * </ul>
 */
final class DumpArchive implements Closeable {
    static final int MAX_ENTRIES = 200_000;
    static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;
    /** 이보다 큰 항목은 압축률이 이 값을 넘으면 거부(과대 압축). */
    static final long RATIO_CHECK_MIN_BYTES = 16L * 1024 * 1024;
    static final long MAX_RATIO = 200;

    private final ZipFile zip;
    private final Manifest manifest;
    private final Map<String, FileEntry> entries = new HashMap<>();
    private final ObjectMapper om = DumpFormat.mapper();

    private DumpArchive(ZipFile zip, Manifest manifest) {
        this.zip = zip;
        this.manifest = manifest;
        for (FileEntry f : manifest.files()) entries.put(f.path(), f);
    }

    /** 열고 전부 검사한다. maxTotalBytes = 풀었을 때 전체 크기 한도. 실패하면 400. */
    static DumpArchive open(Path file, long maxTotalBytes) {
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (ZipException e) {
            throw ApiException.badRequest("zip 파일이 아닙니다: " + e.getMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("덤프를 열 수 없습니다.");
        }
        try {
            DumpArchive a = verify(zip, maxTotalBytes);
            return a;
        } catch (RuntimeException e) {
            try { zip.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static DumpArchive verify(ZipFile zip, long maxTotalBytes) {
        if (zip.size() > MAX_ENTRIES) throw ApiException.badRequest("zip 항목이 너무 많습니다(" + zip.size() + "개, 최대 " + MAX_ENTRIES + ").");
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (n.contains("..") || n.startsWith("/") || n.contains("\\") || n.contains(":") || !DumpFormat.ENTRY_NAME.matcher(n).matches()) {
                throw ApiException.badRequest("허용하지 않는 zip 항목 이름입니다(경로 탈출 의심): " + DumpFormat.cut(n, 120));
            }
            if (!names.add(n)) throw ApiException.badRequest("zip 항목 이름이 중복됩니다: " + n);
        }
        ZipEntry me = zip.getEntry(DumpFormat.MANIFEST);
        if (me == null) throw ApiException.badRequest("manifest.json 이 없습니다. WorkBlog 덤프가 아닙니다.");
        Manifest m;
        try (InputStream in = new Limited(zip.getInputStream(me), MAX_MANIFEST_BYTES, DumpFormat.MANIFEST)) {
            m = DumpFormat.mapper().readValue(in, Manifest.class);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw ApiException.badRequest("manifest.json 을 읽을 수 없습니다: " + DumpFormat.cut(e.getMessage(), 200));
        }
        if (!DumpFormat.FORMAT.equals(m.format())) throw ApiException.badRequest("WorkBlog 덤프가 아닙니다(format=" + m.format() + ").");
        if (m.formatVersion() != DumpFormat.FORMAT_VERSION) throw ApiException.badRequest("지원하지 않는 덤프 형식 버전입니다: " + m.formatVersion());
        if (m.sourceInstanceId() == null || !m.sourceInstanceId().matches("[0-9a-fA-F-]{8,36}")) throw ApiException.badRequest("원본 인스턴스 ID 가 올바르지 않습니다.");
        if (m.files() == null || m.exportedAt() == null || m.kind() == null) throw ApiException.badRequest("manifest.json 에 필수 값이 없습니다.");

        Set<String> listed = new HashSet<>();
        for (FileEntry f : m.files()) {
            if (f.path() == null || !listed.add(f.path())) throw ApiException.badRequest("manifest 파일 목록이 올바르지 않습니다.");
        }
        for (String n : names) {
            if (!n.equals(DumpFormat.MANIFEST) && !listed.contains(n)) throw ApiException.badRequest("manifest 에 없는 항목이 있습니다: " + n);
        }
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        for (FileEntry f : m.files()) {
            ZipEntry e = zip.getEntry(f.path());
            if (e == null) throw ApiException.badRequest("manifest 에 있는 항목이 zip 에 없습니다: " + f.path());
            MessageDigest md = sha256();
            long n = 0;
            try (InputStream in = new Limited(zip.getInputStream(e), Math.max(0, maxTotalBytes - total), f.path())) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                    n += r;
                }
            } catch (ApiException ex) {
                throw ex;
            } catch (IOException ex) {
                throw ApiException.badRequest("zip 항목을 읽을 수 없습니다: " + f.path());
            }
            total += n;
            long compressed = Math.max(1, e.getCompressedSize());
            if (n > RATIO_CHECK_MIN_BYTES && n / compressed > MAX_RATIO) throw ApiException.badRequest("압축률이 비정상적으로 높습니다(과대 압축 의심): " + f.path());
            if (n != f.size() || !HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(f.sha256())) {
                throw ApiException.badRequest("파일이 손상되었거나 바뀌었습니다(SHA-256 불일치): " + f.path());
            }
        }
        return new DumpArchive(zip, m);
    }

    Manifest manifest() { return manifest; }

    boolean has(String path) { return path != null && entries.containsKey(path) && zip.getEntry(path) != null; }

    FileEntry entry(String path) { return entries.get(path); }

    /** 항목 내용을 out 으로 복사하고 바이트 수를 돌려준다(검사 때 확인한 크기만큼만). */
    long copy(String path, OutputStream out) throws IOException {
        FileEntry f = entries.get(path);
        try (InputStream in = new Limited(zip.getInputStream(zip.getEntry(path)), f.size(), path)) {
            return in.transferTo(out);
        }
    }

    /** JSON 배열 항목을 한 원소씩 읽는다. 항목이 없으면 아무것도 하지 않는다(이전 형식·빈 덤프). */
    void forEach(String path, Consumer<JsonNode> each) {
        if (!has(path)) return;
        FileEntry f = entries.get(path);
        try (InputStream in = new Limited(zip.getInputStream(zip.getEntry(path)), f.size(), path);
             JsonParser p = om.getFactory().createParser(in)) {
            if (p.nextToken() != JsonToken.START_ARRAY) throw ApiException.badRequest(path + " 이 JSON 배열이 아닙니다.");
            while (p.nextToken() == JsonToken.START_OBJECT) {
                JsonNode n = om.readTree(p);
                each.accept(n);
            }
        } catch (IOException e) {
            throw ApiException.badRequest(path + " 을 읽을 수 없습니다: " + DumpFormat.cut(e.getMessage(), 200));
        }
    }

    @Override
    public void close() {
        try { zip.close(); } catch (IOException ignored) {}
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 실제로 읽은 바이트가 한도를 넘으면 멈춘다(zip 헤더의 크기 값은 믿지 않는다). */
    static final class Limited extends FilterInputStream {
        private final long max;
        private final String name;
        private long read;

        Limited(InputStream in, long max, String name) {
            super(in);
            this.max = max;
            this.name = name;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) count(r);
            return r;
        }

        private void count(long n) {
            read += n;
            if (read > max) throw ApiException.badRequest("풀었을 때 크기가 한도를 넘습니다(과대 압축 의심): " + name);
        }
    }
}
