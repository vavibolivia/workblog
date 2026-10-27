package com.workblog.ops;

import com.workblog.ops.OpsDtos.StatusResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 운영 API. 공개 /api/health 는 그대로 두고 상세는 여기서만. */
@RestController
@RequestMapping("/api/admin/ops")
@PreAuthorize("hasRole('ADMIN')")
public class OpsController {
    private final OpsStatusService status;
    private final CleanupScheduler cleanup;

    public OpsController(OpsStatusService status, CleanupScheduler cleanup) {
        this.status = status;
        this.cleanup = cleanup;
    }

    /** 가동·메모리·디스크·DB·집계·마지막 정리·최근 오류·경고. */
    @GetMapping("/status")
    public StatusResponse status() {
        return status.status();
    }

    /** 자동 정리를 지금 실행(휴지통 만료 글 영구 삭제 + 고아 첨부 삭제). 결과를 바로 돌려준다. */
    @PostMapping("/cleanup")
    public CleanupScheduler.Result cleanup() {
        return cleanup.run(true);
    }
}
