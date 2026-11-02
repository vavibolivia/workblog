package com.workblog.manmonth;

import com.workblog.auth.CurrentUser;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 계정별로 어느 공수 표·프로젝트에서 무슨 역할인지(인원을 고를 때 '누가 누군지' 알아보는 단서).
 * 공수 표는 로그인한 모두가 볼 수 있으므로 같은 범위만 돌려준다. 계정이 연결된 행만.
 */
@Tag(name = "공수", description = "Man-month 표")
@RestController
@RequestMapping("/api/mm/assignments")
public class MmAssignmentController {
    public record Assignment(long userId, long sheetId, String sheetTitle, String projectName, String role) {}

    private final JdbcTemplate jdbc;

    public MmAssignmentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Operation(summary = "계정별 공수 표 배치", description = "[{userId, sheetId, sheetTitle, projectName, role}] — 계정이 연결된 행만, 표 최근 수정 순")
    public List<Assignment> list(@CurrentUser User user) {
        return jdbc.query("SELECT r.user_id, s.id, s.title, p.name, r.role FROM mm_rows r JOIN mm_sheets s ON s.id = r.sheet_id " +
                        "JOIN mm_projects p ON p.id = r.project_id WHERE r.user_id IS NOT NULL ORDER BY s.updated_at DESC, p.sort_order, p.id",
                (rs, i) -> new Assignment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    @GetMapping("/projects")
    @Operation(summary = "프로젝트 이름 목록", description = "모든 공수 표의 프로젝트 이름(중복 없음, 가나다순). 이벤트 '관련' 고르기용")
    public List<String> projects(@CurrentUser User user) {
        return jdbc.queryForList("SELECT DISTINCT TRIM(name) AS n FROM mm_projects WHERE TRIM(name) <> '' ORDER BY n", String.class);
    }
}
