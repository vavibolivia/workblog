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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 관리 › 업데이트: 프로그램(공개 저장소 릴리스)·데이터(비공개 저장소 릴리스)를 받는 workblog-sync 와 파일로 주고받는다.
 * 앱은 컨테이너 안이라 직접 업데이트하지 않는다. 화면 설정은 {데이터}/sync/settings.json, [지금 업데이트]는 {데이터}/sync/request,
 * 결과는 workblog-sync(호스트, root)가 쓰는 {데이터}/sync/status.json.
 * 데이터 저장소·토큰은 화면에서 입력하면 {데이터}/sync/github.json(600)에 잠깐 두고, workblog-sync 가 바로 읽어
 * 호스트의 sync.env(root 전용)로 옮긴 뒤 지운다. 토큰은 화면으로 다시 보내지 않는다(설정됨/없음만).
 * 프로그램 저장소는 화면에서 바꾸지 못한다 — 업데이트 장치가 그 저장소의 install.sh 를 root 로 실행하므로, 바꾸려면 호스트의 sync.env(root)를 고친다.
 */
@RestController
@RequestMapping("/api/admin/sync")
public class SyncController {
    private static final Logger log = LoggerFactory.getLogger(SyncController.class);
    private static final Set<Integer> INTERVALS = Set.of(0, 15, 60, 360, 1440);
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]{20,255}");

    private final Path dir;
    private final ObjectMapper om = new ObjectMapper();

    public SyncController(WorkblogProperties props) {
        Path storage = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        Path parent = storage.getParent() == null ? storage : storage.getParent();
        this.dir = parent.resolve("sync");
    }

    public record Settings(int intervalMinutes, boolean program, boolean data) {}

    /** token 이 비어 있으면 지금 토큰을 그대로 둔다. dataRepo 를 비우면 데이터 받기를 끈다(토큰도 지움). */
    public record GithubRequest(String dataRepo, String token) {}

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> get() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installed", Files.isDirectory(dir));   // install.sh 가 업데이트 장치를 설치한 서버에만 있다
        m.put("appVersion", OpsStatusService.VERSION.replace("workblog-backend-", ""));
        m.put("settings", settings());
        m.put("requested", Files.exists(dir.resolve("request")));
        JsonNode st = read(dir.resolve("status.json"));
        m.put("status", st);
        // 저장소 이름·토큰 유무는 workblog-sync 가 status.json 에 적어 준 것(토큰 값은 없음)
        JsonNode gh = st == null ? null : st.get("github");
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("programRepo", gh == null ? "" : gh.path("programRepo").asText(""));
        g.put("dataRepo", gh == null ? "" : gh.path("dataRepo").asText(""));
        g.put("tokenSet", gh != null && gh.path("tokenSet").asBoolean(false));
        g.put("pending", Files.exists(dir.resolve("github.json")));
        m.put("github", g);
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

    /** 데이터 저장소·토큰 입력. 파일을 남기고 [지금 업데이트]처럼 요청 → workblog-sync 가 sync.env 로 옮기고 바로 확인한다. */
    @PutMapping("/github")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> github(@RequestBody GithubRequest r) {
        requireInstalled();
        String data = r.dataRepo() == null ? "" : r.dataRepo().trim();
        String token = r.token() == null ? "" : r.token().trim();
        if (!data.isEmpty() && !REPO.matcher(data).matches()) throw ApiException.badRequest("데이터 저장소는 '계정/저장소' 형식입니다(예: vavibolivia/workblog-data).");
        if (!token.isEmpty() && !TOKEN.matcher(token).matches()) throw ApiException.badRequest("토큰 형식이 아닙니다(GitHub 토큰은 영문·숫자·_ 로 된 ghp_… 또는 github_pat_…).");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataRepo", data);
        body.put("token", data.isEmpty() ? "" : token);
        body.put("keepToken", !data.isEmpty() && token.isEmpty());
        try {
            Path tmp = dir.resolve("github.json.tmp");
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
            }
            Files.writeString(tmp, om.writeValueAsString(body), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("github.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(dir.resolve("request"), Instant.now().toString() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("GitHub 설정을 저장하지 못했습니다: " + e.getMessage(), e);
        }
        log.info("GitHub 설정 입력: 데이터 {} · 토큰 {}", data.isEmpty() ? "(끔)" : data, token.isEmpty() ? "유지" : "새로 입력");
        return get();
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
