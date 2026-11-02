package com.workblog.datamove;

import com.workblog.auth.CurrentUser;
import com.workblog.auth.JwtAuthenticationFilter;
import com.workblog.common.ApiException;
import com.workblog.datamove.DataMoveDtos.ExportRequest;
import com.workblog.datamove.DataMoveDtos.Info;
import com.workblog.datamove.DataMoveDtos.Ticket;
import com.workblog.datamove.DataMoveDtos.TransferDetail;
import com.workblog.datamove.DataMoveDtos.UploadResult;
import com.workblog.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Map;

/**
 * 관리자 데이터 이전(다른 망으로 옮기기). ADMIN + 웹 로그인(JWT)만: API 토큰은 /api/admin/** 에서 필터가 막고, 여기서도 한 번 더 본다.
 * 브라우저 내려받기는 1회용 표(/export/ticket → /export/download?ticket=)로, CLI 는 /export 를 바로 부른다.
 */
@RestController
@RequestMapping("/api/admin/data")
public class DataMoveController {
    private final DataMoveService service;

    public DataMoveController(DataMoveService service) { this.service = service; }

    @GetMapping("/info")
    @PreAuthorize("hasRole('ADMIN')")
    public Info info(HttpServletRequest req) {
        requireJwt(req);
        return service.info();
    }

    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TransferDetail transfer(@PathVariable long id, HttpServletRequest req) {
        requireJwt(req);
        return service.transfer(id);
    }

    /** CLI·스크립트: 덤프를 바로 내려받는다. full=true 면 전체, 아니면 since(없으면 직전 기준 내보내기 시각) 이후 변경분. */
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void export(@RequestParam(defaultValue = "false") boolean full,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                       @RequestParam(defaultValue = "true") boolean markBaseline,
                       @CurrentUser User me, HttpServletRequest req, HttpServletResponse res) throws IOException {
        requireJwt(req);
        stream(resolveSince(full, since), markBaseline, me.getId(), me.getUsername(), res);
    }

    @PostMapping("/export/ticket")
    @PreAuthorize("hasRole('ADMIN')")
    public Ticket ticket(@RequestBody ExportRequest body, @CurrentUser User me, HttpServletRequest req) {
        requireJwt(req);
        Instant since = resolveSince(Boolean.TRUE.equals(body.full()), body.since());
        return service.ticket(me, since, body.markBaseline() == null || body.markBaseline());
    }

    /** 표만으로 내려받는다(인증 머리글 없이 주소로 여는 브라우저 내려받기). 표는 2분·1회용, 관리자가 만든 것만. */
    @GetMapping("/export/download")
    public void download(@RequestParam String ticket, HttpServletResponse res) throws IOException {
        DataMoveService.ExportTicket t = service.consumeTicket(ticket);
        if (!service.isActiveAdmin(t.actorId())) throw ApiException.forbidden("권한이 없습니다.");
        stream(t.since(), t.markBaseline(), t.actorId(), t.actorName(), res);
    }

    /** zip 을 요청 본문 그대로(application/zip 또는 octet-stream) 올린다 → 검사 + 미리 보기. 한도는 IMPORT_MAX_MB. */
    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public UploadResult upload(@CurrentUser User me, HttpServletRequest req) throws IOException {
        requireJwt(req);
        return service.upload(req.getInputStream(), req.getContentLengthLong(), me);
    }

    @PostMapping("/import/{uploadId}/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public ImportReport apply(@PathVariable String uploadId, @CurrentUser User me, HttpServletRequest req) {
        requireJwt(req);
        return service.apply(uploadId, me);
    }

    @DeleteMapping("/import/{uploadId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void discard(@PathVariable String uploadId, HttpServletRequest req) {
        requireJwt(req);
        service.discard(uploadId);
    }

    /** 시험용 원본의 매핑·가져오기 기록 삭제(스모크 정리용). 만들어진 데이터는 그대로. */
    @DeleteMapping("/sources/{instanceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> forget(@PathVariable String instanceId, HttpServletRequest req) {
        requireJwt(req);
        return Map.of("deleted", service.forgetSource(instanceId));
    }

    private Instant resolveSince(boolean full, Instant since) {
        if (full) return null;
        if (since != null) return since;
        Instant last = service.lastBaseline();
        if (last == null) throw ApiException.badRequest("직전 내보내기 기록이 없습니다. 처음에는 전체 덤프를 받거나 기준 시각을 지정하세요.");
        return last;
    }

    private void stream(Instant since, boolean markBaseline, long actorId, String actorName, HttpServletResponse res) throws IOException {
        DataMoveService.PreparedExport p = service.prepareExport(since);
        res.setContentType("application/zip");
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(p.dumpName()).build().toString());
        res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        res.setHeader("X-Dump-Name", p.dumpName());
        OutputStream out = res.getOutputStream();
        service.export(p, markBaseline, actorId, actorName, out);
        out.flush();
    }

    private static void requireJwt(HttpServletRequest req) {
        if (!"JWT".equals(req.getAttribute(JwtAuthenticationFilter.AUTH_METHOD_ATTR))) {
            throw ApiException.forbidden("데이터 이전은 웹 로그인(관리자 아이디·비밀번호)으로만 할 수 있습니다. API 토큰은 쓸 수 없습니다.");
        }
    }
}
