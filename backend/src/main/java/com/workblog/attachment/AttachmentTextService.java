package com.workblog.attachment;

import com.workblog.config.WorkblogProperties;
import com.workblog.importer.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 첨부 파일 내용 검색(2026-10-27). 문서형 첨부의 텍스트를 attachments.text_content 에 둔다.
 * 엔티티에는 매핑하지 않는다(첨부 목록을 읽을 때마다 최대 200KB 를 끌어오지 않도록). 모두 JDBC 로 읽고 쓴다.
 * - 업로드: {@link AttachmentService} 가 파일을 저장한 직후 {@link #extractNow} 를 부른다(실패해도 업로드는 성공).
 * - 기존 첨부: 기동 후 백그라운드 스레드가 text_extracted_at 이 비어 있는 것을 50건씩 채운다(실패는 WARN 한 줄 남기고 건너뜀).
 */
@Service
public class AttachmentTextService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentTextService.class);
    static final int BATCH = 50;
    /** 이보다 큰 파일은 추출하지 않는다(메모리 보호). 업로드 최대 크기보다 크게 잡아도 의미 없음. */
    private static final long MAX_SOURCE_BYTES = 50L * 1024 * 1024;
    private static final String EXT_REGEX = "\\.(" + String.join("|", new TreeSet<>(TextExtractor.EXTENSIONS)) + ")$";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Path root;

    public AttachmentTextService(JdbcTemplate jdbc, WorkblogProperties props) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
    }

    /** 방금 저장한 첨부의 텍스트를 꺼내 저장한다. 지원하지 않는 형식이면 아무것도 하지 않는다. */
    public void extractNow(long attachmentId, String storedName, String originalName) {
        if (!TextExtractor.supports(originalName)) return;
        String text;
        try {
            text = extractFile(storedName, originalName);
        } catch (Exception e) {
            log.warn("첨부 텍스트 추출 실패: id={} {} ({})", attachmentId, originalName, e.toString());
            text = null;
        }
        // NUL 은 normalize 에서 지웠으므로 이 UPDATE 가 실패할 일은 사실상 없다(실패하면 업로드 트랜잭션도 실패한다).
        jdbc.update("UPDATE attachments SET text_content = ?, text_extracted_at = now() WHERE id = ?", text, attachmentId);
    }

    private String extractFile(String storedName, String originalName) throws Exception {
        Path p = root.resolve(storedName).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw new java.io.FileNotFoundException(storedName);
        if (Files.size(p) > MAX_SOURCE_BYTES) throw new java.io.IOException("파일이 너무 큼(" + Files.size(p) + "B)");
        return TextExtractor.extract(Files.readAllBytes(p), originalName);
    }

    /** 기동 직후 한 번: 아직 추출하지 않은 문서형 첨부를 채운다. 화면·API 를 막지 않도록 별도 스레드. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread t = new Thread(this::backfill, "attachment-text-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** @return 처리한 건수 */
    int backfill() {
        long lastId = 0;
        int done = 0, failed = 0;
        try {
            while (true) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT id, stored_name, original_name FROM attachments " +
                        "WHERE text_extracted_at IS NULL AND id > ? AND kind <> 'IMAGE' AND lower(original_name) ~ ? ORDER BY id LIMIT " + BATCH,
                        lastId, EXT_REGEX);
                if (rows.isEmpty()) break;
                for (Map<String, Object> r : rows) {
                    long id = ((Number) r.get("id")).longValue();
                    lastId = id;
                    String name = (String) r.get("original_name");
                    String text = null;
                    try {
                        text = extractFile((String) r.get("stored_name"), name);
                    } catch (Exception e) {
                        failed++;
                        log.warn("첨부 텍스트 채우기 건너뜀: id={} {} ({})", id, name, e.toString());
                    }
                    try {
                        jdbc.update("UPDATE attachments SET text_content = ?, text_extracted_at = now() WHERE id = ?", text, id);
                        done++;
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("첨부 텍스트 저장 건너뜀: id={} {} ({})", id, name, e.toString());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("첨부 텍스트 채우기 중단: {}", e.toString());
        }
        if (done > 0 || failed > 0) log.info("첨부 텍스트 채우기 끝: 처리 {} · 실패 {}", done, failed);
        return done;
    }

    /**
     * 검색어 q 가 첨부 내용에 있는 글별로 "파일명 — …일치 부분…" 을 돌려준다(글마다 첫 첨부 하나).
     * postIds 는 이미 열람 권한을 거친 검색 결과의 글들이다.
     */
    public Map<Long, String> matches(List<Long> postIds, String q) {
        if (postIds == null || postIds.isEmpty() || q == null || q.isBlank()) return Map.of();
        String query = q.trim();
        MapSqlParameterSource ps = new MapSqlParameterSource().addValue("ids", postIds).addValue("q", query);
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT post_id, original_name, " +
                "       substring(text_content FROM GREATEST(1, strpos(lower(text_content), lower(:q)) - 60) FOR 200) AS around, " +
                "       strpos(lower(text_content), lower(:q)) AS pos, length(text_content) AS len " +
                "FROM attachments WHERE post_id IN (:ids) AND text_content ILIKE CONCAT('%', :q, '%') ORDER BY post_id, id", ps);
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            long pid = ((Number) r.get("post_id")).longValue();
            if (out.containsKey(pid)) continue;
            int pos = r.get("pos") == null ? 0 : ((Number) r.get("pos")).intValue();
            int len = r.get("len") == null ? 0 : ((Number) r.get("len")).intValue();
            out.put(pid, describe((String) r.get("original_name"), (String) r.get("around"), pos, len));
        }
        return out;
    }

    /** "파일명 — …주변 글자…". strpos 가 0(대소문자 규칙 차이 등)이면 앞부분을 보여 준다. */
    static String describe(String name, String around, int pos, int totalLen) {
        String a = around == null ? "" : around.replaceAll("\\s+", " ").trim();
        int start = Math.max(1, pos - 60);
        boolean cutHead = pos > 0 && start > 1;
        boolean cutTail = start - 1 + (around == null ? 0 : around.length()) < totalLen;
        return name + " — " + (cutHead ? "…" : "") + a + (cutTail ? "…" : "");
    }
}
