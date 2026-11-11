package com.workblog.datamove;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workblog.common.ApiException;
import com.workblog.config.WorkblogProperties;
import com.workblog.ops.OpsStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 관리 › 업데이트: 프로그램(공개 저장소 릴리스)·데이터(비공개 저장소 릴리스)를 받는 workblog-sync 와 파일로 주고받는다.
 * 앱은 컨테이너 안이라 직접 업데이트하지 않는다. 화면 설정은 {데이터}/sync/settings.json, [지금 업데이트]는 {데이터}/sync/request,
 * 결과는 workblog-sync(호스트, root)가 쓰는 {데이터}/sync/status.json. 저장소 이름·토큰은 호스트의 sync.env 에만 둔다(화면에 없음).
 */
@RestController
@RequestMapping("/api/admin/sync")
public class SyncController {
    private static final Logger log = LoggerFactory.getLogger(SyncController.class);
    private static final Set<Integer> INTERVALS = Set.of(0, 15, 60, 360, 1440);

    private final Path dir;
    private final ObjectMapper om = new ObjectMapper();

    public SyncController(WorkblogProperties props) {
        Path storage = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        Path parent = storage.getParent() == null ? storage : storage.getParent();
        this.dir = parent.resolve("sync");
    }

    public record Settings(int intervalMinutes, boolean program, boolean data) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> get() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installed", Files.isDirectory(dir));   // install.sh 가 업데이트 장치를 설치한 서버에만 있다
        m.put("appVersion", OpsStatusService.VERSION.replace("workblog-backend-", ""));
        m.put("settings", settings());
        m.put("requested", Files.exists(dir.resolve("request")));
        m.put("status", read(dir.resolve("status.json")));
        return m;
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Settings save(@RequestBody Settings s) {
        requireInstalled();
        if (!INTERVALS.contains(s.intervalMinutes())) throw ApiException.badRequest("주기는 0(끔)·15·60·360·1440 분 중 하나입니다.");
        try {
            Path tmp = dir.resolve("settings.json.tmp");
            Files.writeString(tmp, om.writeValueAsString(s), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("업데이트 설정을 저장하지 못했습니다: " + e.getMessage(), e);
        }
        log.info("업데이트 설정: 주기 {}분 · 프로그램 {} · 데이터 {}", s.intervalMinutes(), s.program(), s.data());
        return s;
    }

    /** [지금 업데이트]: 요청 파일을 남기면 호스트의 workblog-sync.path 가 바로 실행한다. */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> run() {
        requireInstalled();
        try {
            Files.writeString(dir.resolve("request"), Instant.now().toString() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("업데이트 요청을 남기지 못했습니다: " + e.getMessage(), e);
        }
        log.info("지금 업데이트 요청");
        return get();
    }

    private void requireInstalled() {
        if (!Files.isDirectory(dir))
            throw ApiException.badRequest("이 서버에는 업데이트 장치가 없습니다(install.sh 로 설치한 서버에서만 — 이 서버는 다른 방식으로 배포됨).");
    }

    private Settings settings() {
        JsonNode n = read(dir.resolve("settings.json"));
        if (n == null) return new Settings(0, true, true);
        int iv = n.path("intervalMinutes").asInt(0);
        return new Settings(INTERVALS.contains(iv) ? iv : 0, n.path("program").asBoolean(true), n.path("data").asBoolean(true));
    }

    private JsonNode read(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            return om.readTree(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
