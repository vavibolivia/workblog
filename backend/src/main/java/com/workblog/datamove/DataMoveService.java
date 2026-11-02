package com.workblog.datamove;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workblog.attachment.AttachmentTextService;
import com.workblog.common.ApiException;
import com.workblog.config.WorkblogProperties;
import com.workblog.datamove.DataMoveDtos.Info;
import com.workblog.datamove.DataMoveDtos.ManifestSummary;
import com.workblog.datamove.DataMoveDtos.Ticket;
import com.workblog.datamove.DataMoveDtos.TransferDetail;
import com.workblog.datamove.DataMoveDtos.TransferRow;
import com.workblog.datamove.DataMoveDtos.UploadResult;
import com.workblog.datamove.DumpFormat.Manifest;
import com.workblog.ops.OpsStatusService;
import com.workblog.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.workblog.datamove.DumpFormat.instant;
import static com.workblog.datamove.DumpFormat.ts;

/**
 * 관리자 데이터 이전: 덤프 내보내기(전체·업데이트)와 가져오기(올리기 → 미리 보기 → 적용).
 * 올린 zip 은 임시 폴더({저장소 상위}/imports 또는 IMPORT_TMP_DIR)에 두고 2시간 뒤(또는 적용·취소 때) 지운다.
 */
@Service
public class DataMoveService {
    private static final Logger log = LoggerFactory.getLogger(DataMoveService.class);
    private static final Duration UPLOAD_TTL = Duration.ofHours(2);
    private static final Duration TICKET_TTL = Duration.ofMinutes(2);

    private record Pending(Path file, Long actorId, Instant createdAt) {}

    public record ExportTicket(long actorId, String actorName, Instant since, boolean markBaseline, Instant expiresAt) {}

    private final JdbcTemplate jdbc;
    private final DataSource ds;
    private final PlatformTransactionManager tm;
    private final InstanceIdService instance;
    private final AttachmentTextService texts;
    private final Path storageRoot;
    private final Path tmpDir;
    private final long importMaxMb;
    private final ObjectMapper om = DumpFormat.mapper();
    private final Map<String, Pending> uploads = new ConcurrentHashMap<>();
    private final Map<String, ExportTicket> tickets = new ConcurrentHashMap<>();
    private final ReentrantLock applyLock = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();

    public DataMoveService(JdbcTemplate jdbc, DataSource ds, PlatformTransactionManager tm, InstanceIdService instance,
                           AttachmentTextService texts, WorkblogProperties props,
                           @Value("${workblog.datamove.import-max-mb:2048}") long importMaxMb,
                           @Value("${workblog.datamove.tmp-dir:}") String tmpDir) {
        this.jdbc = jdbc;
        this.ds = ds;
        this.tm = tm;
        this.instance = instance;
        this.texts = texts;
        this.storageRoot = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        Path parent = storageRoot.getParent() == null ? storageRoot : storageRoot.getParent();
        this.tmpDir = (tmpDir == null || tmpDir.isBlank() ? parent.resolve("imports") : Path.of(tmpDir)).toAbsolutePath().normalize();
        this.importMaxMb = importMaxMb;
        try {
            Files.createDirectories(this.tmpDir);
            // 재시작 전에 올려 둔 덤프는 더 이상 쓸 수 없다 → 지운다
            try (DirectoryStream<Path> ds2 = Files.newDirectoryStream(this.tmpDir, "*.zip")) {
                for (Path p : ds2) Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("가져오기 임시 폴더를 준비하지 못했습니다: {} ({})", this.tmpDir, e.toString());
        }
    }

    // ---------- 정보 · 기록 ----------

    public Info info() {
        return new Info(instance.get(), lastBaseline(), importMaxMb, history(30));
    }

    /** 다음 업데이트 덤프의 기본 기준 시각: 기준으로 표시한 마지막 성공 내보내기 시각. */
    public Instant lastBaseline() {
        List<Instant> l = jdbc.query("SELECT max(exported_at) AS t FROM data_transfers WHERE direction = 'EXPORT' AND status = 'OK' AND baseline = TRUE",
                (rs, i) -> instant(rs, "t"));
        return l.isEmpty() ? null : l.get(0);
    }

    public List<TransferRow> history(int limit) {
        return jdbc.query("SELECT * FROM data_transfers ORDER BY started_at DESC, id DESC LIMIT ?", (rs, i) -> row(rs), limit);
    }

    public TransferDetail transfer(long id) {
        List<TransferDetail> l = jdbc.query("SELECT * FROM data_transfers WHERE id = ?", (rs, i) -> {
            String rep = rs.getString("report");
            Object parsed = null;
            try { parsed = rep == null ? null : om.readTree(rep); } catch (JsonProcessingException ignored) {}
            return new TransferDetail(row(rs), parsed);
        }, id);
        if (l.isEmpty()) throw ApiException.notFound("기록");
        return l.get(0);
    }

    private static TransferRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferRow(rs.getLong("id"), rs.getString("direction"), rs.getString("dump_kind"), rs.getString("action"), rs.getString("dump_name"),
                rs.getString("source_instance"), instant(rs, "since_at"), instant(rs, "exported_at"), rs.getBoolean("baseline"), rs.getString("status"),
                rs.getString("actor_name"), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("summary"));
    }

    private long startRecord(String direction, String kind, String action, String dumpName, String source, Instant since, Instant exportedAt,
                             boolean baseline, User actor) {
        KeyHolderInsert ins = new KeyHolderInsert(jdbc);
        return ins.insert("INSERT INTO data_transfers (direction, dump_kind, action, dump_name, source_instance, since_at, exported_at, baseline, status, actor_id, actor_name, started_at) " +
                        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                direction, kind, action, DumpFormat.cut(dumpName, 200), source, ts(since), ts(exportedAt), baseline, "RUNNING", actor.getId(),
                DumpFormat.cut(actor.getUsername(), 100), ts(Instant.now()));
    }

    private void finishRecord(long id, String status, String summary, Object report) {
        String json = null;
        if (report != null) {
            try { json = om.writeValueAsString(report); } catch (JsonProcessingException e) { json = null; }
        }
        jdbc.update("UPDATE data_transfers SET status = ?, summary = ?, report = ?, finished_at = ? WHERE id = ?",
                status, DumpFormat.cut(summary, 500), json, ts(Instant.now()), id);
    }

    // ---------- 내보내기 ----------

    /** 브라우저 내려받기용 1회용 표(2분). 큰 파일을 메모리에 받지 않고 주소로 바로 내려받게 한다. */
    public Ticket ticket(User actor, Instant since, boolean markBaseline) {
        tickets.values().removeIf(t -> t.expiresAt().isBefore(Instant.now()));
        byte[] b = new byte[32];
        random.nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        tickets.put(t, new ExportTicket(actor.getId(), actor.getUsername(), since, markBaseline, Instant.now().plus(TICKET_TTL)));
        return new Ticket(t, TICKET_TTL.getSeconds());
    }

    public ExportTicket consumeTicket(String t) {
        ExportTicket x = t == null ? null : tickets.remove(t);
        if (x == null || x.expiresAt().isBefore(Instant.now())) throw ApiException.forbidden("내려받기 표가 없거나 만료되었습니다. 화면에서 다시 내보내기를 누르세요.");
        return x;
    }

    /** 표를 쓰는 시점에도 활성 관리자인지(표를 받은 뒤 권한이 바뀌었을 수 있다). */
    public boolean isActiveAdmin(long userId) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id = ? AND role = 'ADMIN' AND active = TRUE", Long.class, userId).isEmpty();
    }

    /** 이름을 미리 정해 응답 머리글에 쓸 수 있게 한다. */
    public PreparedExport prepareExport(Instant since) {
        if (since != null && since.isAfter(Instant.now())) throw ApiException.badRequest("기준 시각이 미래입니다.");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DumpFormat.Kind kind = since == null ? DumpFormat.Kind.FULL : DumpFormat.Kind.UPDATE;
        return new PreparedExport(since, at, DumpFormat.dumpName(instance.get(), at, kind));
    }

    public record PreparedExport(Instant since, Instant exportedAt, String dumpName) {}

    public Manifest export(PreparedExport p, boolean markBaseline, long actorId, String actorName, OutputStream out) {
        String kind = p.since() == null ? "FULL" : "UPDATE";
        long rec = new KeyHolderInsert(jdbc).insert("INSERT INTO data_transfers (direction, dump_kind, action, dump_name, source_instance, since_at, exported_at, baseline, status, actor_id, actor_name, started_at) " +
                        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "EXPORT", kind, "EXPORT", p.dumpName(), instance.get(), ts(p.since()), ts(p.exportedAt()), markBaseline, "RUNNING", actorId,
                DumpFormat.cut(actorName, 100), ts(Instant.now()));
        log.info("데이터 내보내기 시작: {} by {} (since={}, 기준 기록={})", p.dumpName(), actorName, p.since(), markBaseline);
        try {
            Manifest m = new DumpWriter(ds, tm, storageRoot).write(out, instance.get(), OpsStatusService.VERSION, p.since(), p.exportedAt());
            out.flush();
            finishRecord(rec, "OK", exportSummary(m.counts()), Map.of("counts", m.counts(), "files", m.files().size()));
            log.info("데이터 내보내기 완료: {} {}", p.dumpName(), m.counts());
            return m;
        } catch (Exception e) {
            finishRecord(rec, "FAILED", "실패: " + DumpFormat.cut(String.valueOf(e.getMessage()), 300), null);
            log.warn("데이터 내보내기 실패: {} ({})", p.dumpName(), e.toString());
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private static String exportSummary(Map<String, Long> c) {
        return "사용자 " + c.getOrDefault("users", 0L) + " · 카테고리 " + c.getOrDefault("categories", 0L) + " · 글 " + c.getOrDefault("posts", 0L)
                + " · 이력 " + c.getOrDefault("revisions", 0L) + " · 댓글 " + c.getOrDefault("comments", 0L) + " · 첨부 " + c.getOrDefault("attachments", 0L);
    }

    // ---------- 가져오기 ----------

    public long importMaxBytes() { return importMaxMb * 1024L * 1024L; }

    /** 덤프를 받아 임시 파일로 저장 → 검사 → 미리 보기(아무것도 바꾸지 않음). */
    public UploadResult upload(InputStream body, long contentLength, User actor) {
        cleanupExpired();
        long max = importMaxBytes();
        if (contentLength > max) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "덤프가 너무 큽니다. 최대 " + importMaxMb + "MB (설정 IMPORT_MAX_MB).");
        String id = UUID.randomUUID().toString();
        Path file = tmpDir.resolve(id + ".zip");
        try {
            Files.createDirectories(tmpDir);
            long n = 0;
            byte[] buf = new byte[64 * 1024];
            try (OutputStream o = Files.newOutputStream(file)) {
                int r;
                while ((r = body.read(buf)) > 0) {
                    n += r;
                    if (n > max) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "덤프가 너무 큽니다. 최대 " + importMaxMb + "MB (설정 IMPORT_MAX_MB).");
                    o.write(buf, 0, r);
                }
            }
            if (n == 0) throw ApiException.badRequest("파일이 비어 있습니다.");
        } catch (IOException e) {
            delete(file);
            throw new ApiException(HttpStatus.BAD_REQUEST, "덤프를 받는 중 끊겼습니다.");
        } catch (RuntimeException e) {
            delete(file);
            throw e;
        }
        try (DumpArchive a = openChecked(file)) {
            ImportReport preview = new DumpImporter(jdbc, null, storageRoot, a, false, null).run();
            Manifest m = a.manifest();
            long rec = startRecord("IMPORT", m.kind().name(), "PREVIEW", m.dumpName(), m.sourceInstanceId(), m.since(), m.exportedAt(), false, actor);
            finishRecord(rec, preview.status, importSummary(preview), preview);
            uploads.put(id, new Pending(file, actor.getId(), Instant.now()));
            log.info("데이터 가져오기 미리 보기: {} by {} → {}", m.dumpName(), actor.getUsername(), importSummary(preview));
            return new UploadResult(id, summary(m), preview);
        } catch (RuntimeException e) {
            delete(file);
            throw e;
        }
    }

    /** 미리 본 덤프를 적용한다. 한 번에 하나만. */
    public ImportReport apply(String uploadId, User actor) {
        Pending p = uploads.get(uploadId);
        if (p == null || !Files.exists(p.file())) throw ApiException.notFound("올린 덤프(2시간이 지났거나 이미 적용함)");
        if (!applyLock.tryLock()) throw ApiException.conflict("다른 가져오기가 진행 중입니다. 끝난 뒤 다시 시도하세요.");
        try {
            uploads.remove(uploadId);
            try (DumpArchive a = openChecked(p.file())) {
                Manifest m = a.manifest();
                long rec = startRecord("IMPORT", m.kind().name(), "APPLY", m.dumpName(), m.sourceInstanceId(), m.since(), m.exportedAt(), false, actor);
                log.info("데이터 가져오기 적용 시작: {} by {}", m.dumpName(), actor.getUsername());
                ImportReport rep = new DumpImporter(jdbc, new TransactionTemplate(tm), storageRoot, a, true, texts::extractNow).run();
                finishRecord(rec, rep.status, importSummary(rep), rep);
                log.info("데이터 가져오기 적용 {}: {} → {}", rep.status, m.dumpName(), importSummary(rep));
                return rep;
            } finally {
                delete(p.file());
            }
        } finally {
            applyLock.unlock();
        }
    }

    public void discard(String uploadId) {
        Pending p = uploads.remove(uploadId);
        if (p != null) delete(p.file());
    }

    /** 시험용 원본(스모크 등)의 매핑과 가져오기 기록을 지운다. 이 서버에 만들어진 데이터는 지우지 않는다. */
    public int forgetSource(String sourceInstanceId) {
        if (sourceInstanceId == null || !sourceInstanceId.matches("[0-9a-fA-F-]{8,36}")) throw ApiException.badRequest("인스턴스 ID 형식이 아닙니다.");
        int n = jdbc.update("DELETE FROM data_import_map WHERE source_instance = ?", sourceInstanceId);
        jdbc.update("DELETE FROM data_transfers WHERE direction = 'IMPORT' AND source_instance = ?", sourceInstanceId);
        log.info("가져오기 매핑 삭제: 원본 {} ({}건)", sourceInstanceId, n);
        return n;
    }

    private DumpArchive openChecked(Path file) {
        // 풀었을 때 전체 크기 한도: 업로드 한도의 4배(JSON 은 잘 압축된다)
        DumpArchive a = DumpArchive.open(file, importMaxBytes() * 4);
        if (instance.get().equalsIgnoreCase(a.manifest().sourceInstanceId())) {
            a.close();
            throw ApiException.badRequest("이 서버가 만든 덤프입니다. 자기 덤프는 가져올 수 없습니다(다른 서버에서 내보낸 덤프를 올리세요).");
        }
        return a;
    }

    private static ManifestSummary summary(Manifest m) {
        long total = m.files().stream().mapToLong(DumpFormat.FileEntry::size).sum();
        return new ManifestSummary(m.dumpName(), m.sourceInstanceId(), m.kind().name(), m.appVersion(), m.exportedAt(), m.since(), m.counts(), total);
    }

    static String importSummary(ImportReport r) {
        long c = 0, u = 0, s = 0, k = 0, f = 0;
        for (ImportReport.Counts x : r.counts.values()) { c += x.created; u += x.updated; s += x.skipped; k += x.conflict; f += x.failed; }
        ImportReport.Counts posts = r.counts.getOrDefault("posts", new ImportReport.Counts());
        String head = r.applied ? "적용" : "미리 보기";
        String tail = "FAILED".equals(r.status) ? " · 실패 단계 " + r.failedStage : "";
        return head + " — 글 추가 " + posts.created + " · 갱신 " + posts.updated + " · 충돌 " + posts.conflict
                + " | 전체 추가 " + c + " · 갱신 " + u + " · 건너뜀 " + s + " · 충돌 " + k + " · 실패 " + f + tail;
    }

    private void cleanupExpired() {
        Instant limit = Instant.now().minus(UPLOAD_TTL);
        uploads.entrySet().removeIf(e -> {
            if (e.getValue().createdAt().isBefore(limit)) { delete(e.getValue().file()); return true; }
            return false;
        });
    }

    private static void delete(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }

    /** 새 id 를 돌려받는 INSERT (PostgreSQL·H2 공통). */
    private record KeyHolderInsert(JdbcTemplate jdbc) {
        long insert(String sql, Object... args) {
            org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
                new org.springframework.jdbc.core.ArgumentPreparedStatementSetter(args).setValues(ps);
                return ps;
            }, kh);
            Map<String, Object> keys = kh.getKeys();
            Object v = keys == null || keys.isEmpty() ? null : keys.size() == 1 ? keys.values().iterator().next() : keys.getOrDefault("id", keys.get("ID"));
            return ((Number) v).longValue();
        }
    }
}
