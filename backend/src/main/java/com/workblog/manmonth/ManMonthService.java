package com.workblog.manmonth;

import com.workblog.common.ApiException;
import com.workblog.manmonth.ManMonthDtos.*;
import com.workblog.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 공수(Man-month) 표. 가로 = 주(월요일 시작), 세로 = 프로젝트별 인원, 칸 = 그 주 투입률(0~1.0).
 * 조회는 로그인한 모두, 수정은 관리자 · 표 주인 · 편집자, 표 설정·편집자 지정·삭제는 관리자 · 주인, 메인 게시는 관리자.
 */
@Service
@Transactional
public class ManMonthService {
    static final BigDecimal MAX = new BigDecimal("1.00");

    private final JdbcTemplate jdbc;

    public ManMonthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Sheet(long id, String title, String description, LocalDate startDate, int weeks, boolean featured, Long ownerId,
                         String ownerName, Instant updatedAt) {}

    // ---------- 조회 ----------

    @Transactional(readOnly = true)
    public List<SheetSummary> list(User viewer, boolean featuredOnly) {
        List<Sheet> sheets = jdbc.query(SHEET_SQL + (featuredOnly ? " WHERE s.featured = TRUE" : "") + " ORDER BY s.featured DESC, s.updated_at DESC",
                (rs, i) -> sheet(rs));
        List<SheetSummary> out = new ArrayList<>();
        for (Sheet s : sheets) {
            out.add(new SheetSummary(s.id(), s.title(), s.description(), s.startDate(), s.weeks(), s.featured(), s.ownerName(), s.updatedAt(), canEdit(s, viewer)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SheetDetail get(long id, User viewer) {
        Sheet s = load(id);
        List<UserRef> editors = jdbc.query("SELECT u.id, u.username, u.display_name, u.department FROM mm_sheet_editors e JOIN users u ON u.id = e.user_id " +
                "WHERE e.sheet_id = ? ORDER BY u.display_name", (rs, i) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        List<ProjectDto> projects = jdbc.query("SELECT id, name, sort_order FROM mm_projects WHERE sheet_id = ? ORDER BY sort_order, id",
                (rs, i) -> new ProjectDto(rs.getLong(1), rs.getString(2), rs.getInt(3)), id);
        Map<Long, Map<Integer, BigDecimal>> cells = new HashMap<>();
        jdbc.query("SELECT c.row_id, c.week, c.value FROM mm_cells c JOIN mm_rows r ON r.id = c.row_id WHERE r.sheet_id = ? AND c.week < ?", rs -> {
            cells.computeIfAbsent(rs.getLong(1), k -> new TreeMap<>()).put(rs.getInt(2), rs.getBigDecimal(3).stripTrailingZeros());
        }, id, s.weeks());
        List<RowDto> rows = jdbc.query("SELECT id, project_id, user_id, person_name, department, role, sort_order FROM mm_rows WHERE sheet_id = ? ORDER BY sort_order, id",
                (rs, i) -> {
                    long rid = rs.getLong(1);
                    Long uid = rs.getLong(3);
                    if (rs.wasNull()) uid = null;
                    return new RowDto(rid, rs.getLong(2), uid, rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7),
                            cells.getOrDefault(rid, Map.of()));
                }, id);
        List<ChangeDto> changes = jdbc.query("SELECT actor_name, summary, created_at FROM mm_changes WHERE sheet_id = ? ORDER BY created_at DESC, id DESC LIMIT 30",
                (rs, i) -> new ChangeDto(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), id);
        return new SheetDetail(s.id(), s.title(), s.description(), s.startDate(), s.weeks(), s.featured(), s.ownerId(), s.ownerName(), s.updatedAt(),
                canEdit(s, viewer), canManage(s, viewer), editors, projects, rows, changes);
    }

    // ---------- 표 ----------

    public SheetDetail create(CreateSheetRequest req, User actor) {
        LocalDate start = monday(req.startDate());
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("INSERT INTO mm_sheets (title, description, start_date, weeks, owner_id) VALUES (?,?,?,?,?)", new String[]{"id"});
            ps.setString(1, req.title().trim());
            ps.setString(2, blankToNull(req.description()));
            ps.setDate(3, Date.valueOf(start));
            ps.setInt(4, req.weeks());
            ps.setLong(5, actor.getId());
            return ps;
        }, kh);
        long id = Objects.requireNonNull(kh.getKey()).longValue();
        log(id, actor, "표 만듦: 시작 " + start + " · " + req.weeks() + "주");
        return get(id, actor);
    }

    public SheetDetail update(long id, UpdateSheetRequest req, User actor) {
        Sheet s = load(id);
        requireManage(s, actor);
        List<String> what = new ArrayList<>();
        if (req.title() != null && !req.title().isBlank()) {
            jdbc.update("UPDATE mm_sheets SET title = ? WHERE id = ?", req.title().trim(), id);
            what.add("제목");
        }
        if (req.description() != null) {
            jdbc.update("UPDATE mm_sheets SET description = ? WHERE id = ?", blankToNull(req.description()), id);
            what.add("설명");
        }
        LocalDate start = s.startDate();
        if (req.startDate() != null && !monday(req.startDate()).equals(s.startDate())) {
            start = monday(req.startDate());
            // 값은 날짜 기준으로 유지: 새 시작이 n주 늦으면 기존 주 번호에서 n 을 뺀다(PK 충돌을 피하려 두 번에 나눠 이동)
            int shift = (int) ChronoUnit.WEEKS.between(s.startDate(), start);
            jdbc.update("UPDATE mm_cells SET week = week + 100000 - ? WHERE row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", shift, id);
            jdbc.update("DELETE FROM mm_cells WHERE week < 100000 AND row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", id);
            jdbc.update("UPDATE mm_cells SET week = week - 100000 WHERE row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", id);
            jdbc.update("UPDATE mm_sheets SET start_date = ? WHERE id = ?", Date.valueOf(start), id);
            what.add("시작 " + start);
        }
        int weeks = req.weeks() != null ? req.weeks() : s.weeks();
        if (req.weeks() != null && req.weeks() != s.weeks()) {
            jdbc.update("UPDATE mm_sheets SET weeks = ? WHERE id = ?", weeks, id);
            what.add(weeks + "주");
        }
        if (req.startDate() != null || req.weeks() != null) {
            jdbc.update("DELETE FROM mm_cells WHERE week >= ? AND row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", weeks, id);
        }
        if (req.featured() != null && req.featured() != s.featured()) {
            if (!actor.isAdmin()) throw ApiException.forbidden("메인 게시는 관리자만 정할 수 있습니다.");
            jdbc.update("UPDATE mm_sheets SET featured = ? WHERE id = ?", req.featured(), id);
            what.add(req.featured() ? "메인 게시" : "메인 게시 해제");
        }
        if (!what.isEmpty()) {
            touch(id);
            log(id, actor, "표 설정: " + String.join(" · ", what));
        }
        return get(id, actor);
    }

    public void delete(long id, User actor) {
        requireManage(load(id), actor);
        jdbc.update("DELETE FROM mm_sheets WHERE id = ?", id);
    }

    public SheetDetail setEditors(long id, EditorsRequest req, User actor) {
        requireManage(load(id), actor);
        jdbc.update("DELETE FROM mm_sheet_editors WHERE sheet_id = ?", id);
        Set<Long> ids = new LinkedHashSet<>(req.userIds());
        for (Long uid : ids) {
            if (uid == null) continue;
            if (jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, uid) == 0) throw ApiException.badRequest("없는 사용자입니다: " + uid);
            jdbc.update("INSERT INTO mm_sheet_editors (sheet_id, user_id) VALUES (?,?)", id, uid);
        }
        touch(id);
        log(id, actor, "편집자 지정: " + ids.size() + "명");
        return get(id, actor);
    }

    // ---------- 프로젝트 ----------

    public SheetDetail addProject(long id, ProjectRequest req, User actor) {
        requireEdit(load(id), actor);
        int order = req.sortOrder() != null ? req.sortOrder()
                : jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM mm_projects WHERE sheet_id = ?", Integer.class, id);
        jdbc.update("INSERT INTO mm_projects (sheet_id, name, sort_order) VALUES (?,?,?)", id, req.name().trim(), order);
        touch(id);
        log(id, actor, "프로젝트 추가: " + req.name().trim());
        return get(id, actor);
    }

    public SheetDetail updateProject(long id, long projectId, ProjectRequest req, User actor) {
        requireEdit(load(id), actor);
        requireProject(id, projectId);
        jdbc.update("UPDATE mm_projects SET name = ?, sort_order = COALESCE(?, sort_order) WHERE id = ?", req.name().trim(), req.sortOrder(), projectId);
        touch(id);
        log(id, actor, "프로젝트 수정: " + req.name().trim());
        return get(id, actor);
    }

    public SheetDetail deleteProject(long id, long projectId, User actor) {
        requireEdit(load(id), actor);
        String name = requireProject(id, projectId);
        jdbc.update("DELETE FROM mm_projects WHERE id = ?", projectId);
        touch(id);
        log(id, actor, "프로젝트 삭제: " + name);
        return get(id, actor);
    }

    // ---------- 인원(행) ----------

    public SheetDetail addRow(long id, RowRequest req, User actor) {
        requireEdit(load(id), actor);
        if (req.projectId() == null) throw ApiException.badRequest("프로젝트를 고르세요.");
        requireProject(id, req.projectId());
        String[] p = person(req);
        int order = req.sortOrder() != null ? req.sortOrder()
                : jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM mm_rows WHERE sheet_id = ?", Integer.class, id);
        jdbc.update("INSERT INTO mm_rows (sheet_id, project_id, user_id, person_name, department, role, sort_order) VALUES (?,?,?,?,?,?,?)",
                id, req.projectId(), req.userId(), p[0], p[1], blankToNull(req.role()), order);
        touch(id);
        log(id, actor, "인원 추가: " + p[0]);
        return get(id, actor);
    }

    public SheetDetail updateRow(long id, long rowId, RowRequest req, User actor) {
        requireEdit(load(id), actor);
        requireRow(id, rowId);
        if (req.projectId() != null) requireProject(id, req.projectId());
        String[] p = person(req);
        jdbc.update("UPDATE mm_rows SET project_id = COALESCE(?, project_id), user_id = ?, person_name = ?, department = ?, role = ?, " +
                "sort_order = COALESCE(?, sort_order) WHERE id = ?", req.projectId(), req.userId(), p[0], p[1], blankToNull(req.role()), req.sortOrder(), rowId);
        touch(id);
        log(id, actor, "인원 수정: " + p[0]);
        return get(id, actor);
    }

    public SheetDetail deleteRow(long id, long rowId, User actor) {
        requireEdit(load(id), actor);
        String name = requireRow(id, rowId);
        jdbc.update("DELETE FROM mm_rows WHERE id = ?", rowId);
        touch(id);
        log(id, actor, "인원 삭제: " + name);
        return get(id, actor);
    }

    // ---------- 칸 ----------

    /** 여러 칸을 한 번에(붙여넣기 포함). 같은 칸을 동시에 고치면 나중 저장이 남는다. */
    public SheetDetail setCells(long id, CellsRequest req, User actor) {
        Sheet s = load(id);
        requireEdit(s, actor);
        Set<Long> rowIds = new HashSet<>(jdbc.queryForList("SELECT id FROM mm_rows WHERE sheet_id = ?", Long.class, id));
        int set = 0, cleared = 0;
        for (CellChange c : req.changes()) {
            if (!rowIds.contains(c.rowId())) throw ApiException.badRequest("이 표의 행이 아닙니다: " + c.rowId());
            if (c.week() < 0 || c.week() >= s.weeks()) throw ApiException.badRequest("주 범위 밖입니다: " + c.week());
            BigDecimal v = normalize(c.value());
            if (v == null) {
                cleared += jdbc.update("DELETE FROM mm_cells WHERE row_id = ? AND week = ?", c.rowId(), c.week());
            } else {
                upsert(c.rowId(), c.week(), v);
                set++;
            }
        }
        touch(id);
        log(id, actor, "칸 수정: " + set + "칸" + (cleared > 0 ? " · 비움 " + cleared + "칸" : ""));
        return get(id, actor);
    }

    void upsert(long rowId, int week, BigDecimal v) {
        if (jdbc.update("UPDATE mm_cells SET value = ? WHERE row_id = ? AND week = ?", v, rowId, week) == 0) {
            jdbc.update("INSERT INTO mm_cells (row_id, week, value) VALUES (?,?,?)", rowId, week, v);
        }
    }

    /** 0~1.0, 소수 둘째 자리. 0 이나 null 은 '비움'. */
    static BigDecimal normalize(BigDecimal v) {
        if (v == null || v.signum() == 0) return null;
        if (v.signum() < 0 || v.compareTo(MAX) > 0) throw ApiException.badRequest("투입률은 0 ~ 1.0 입니다: " + v.toPlainString());
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- 권한 · 공통 ----------

    boolean canEdit(Sheet s, User u) {
        if (u.isAdmin() || Objects.equals(s.ownerId(), u.getId())) return true;
        return jdbc.queryForObject("SELECT count(*) FROM mm_sheet_editors WHERE sheet_id = ? AND user_id = ?", Integer.class, s.id(), u.getId()) > 0;
    }

    boolean canManage(Sheet s, User u) {
        return u.isAdmin() || Objects.equals(s.ownerId(), u.getId());
    }

    void requireEdit(Sheet s, User u) {
        if (!canEdit(s, u)) throw ApiException.forbidden("이 표의 편집자가 아닙니다. 표 주인이나 관리자에게 편집 권한을 요청하세요.");
    }

    void requireManage(Sheet s, User u) {
        if (!canManage(s, u)) throw ApiException.forbidden("표 설정·편집자 지정·삭제는 표 주인과 관리자만 할 수 있습니다.");
    }

    Sheet load(long id) {
        List<Sheet> l = jdbc.query(SHEET_SQL + " WHERE s.id = ?", (rs, i) -> sheet(rs), id);
        if (l.isEmpty()) throw ApiException.notFound("공수 표");
        return l.get(0);
    }

    String requireProject(long sheetId, long projectId) {
        List<String> l = jdbc.queryForList("SELECT name FROM mm_projects WHERE id = ? AND sheet_id = ?", String.class, projectId, sheetId);
        if (l.isEmpty()) throw ApiException.notFound("프로젝트");
        return l.get(0);
    }

    String requireRow(long sheetId, long rowId) {
        List<String> l = jdbc.queryForList("SELECT person_name FROM mm_rows WHERE id = ? AND sheet_id = ?", String.class, rowId, sheetId);
        if (l.isEmpty()) throw ApiException.notFound("인원 행");
        return l.get(0);
    }

    /** [이름, 소속]. 계정을 고르면 계정 값, 직접 준 값이 있으면 그 값. */
    String[] person(RowRequest req) {
        String name = blankToNull(req.personName());
        String dept = blankToNull(req.department());
        if (req.userId() != null) {
            List<String[]> u = jdbc.query("SELECT display_name, department FROM users WHERE id = ?", (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, req.userId());
            if (u.isEmpty()) throw ApiException.badRequest("없는 사용자입니다: " + req.userId());
            if (name == null) name = u.get(0)[0];
            if (dept == null) dept = u.get(0)[1];
        }
        if (name == null) throw ApiException.badRequest("이름을 적거나 계정을 고르세요.");
        return new String[]{name, dept};
    }

    void touch(long id) {
        jdbc.update("UPDATE mm_sheets SET updated_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
    }

    void log(long id, User actor, String summary) {
        jdbc.update("INSERT INTO mm_changes (sheet_id, actor_id, actor_name, summary) VALUES (?,?,?,?)", id, actor.getId(),
                actor.getDisplayName(), summary.length() > 500 ? summary.substring(0, 500) : summary);
    }

    static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final String SHEET_SQL = "SELECT s.id, s.title, s.description, s.start_date, s.weeks, s.featured, s.owner_id, u.display_name, s.updated_at " +
            "FROM mm_sheets s LEFT JOIN users u ON u.id = s.owner_id";

    private static Sheet sheet(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long owner = rs.getLong(7);
        if (rs.wasNull()) owner = null;
        return new Sheet(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getInt(5), rs.getBoolean(6), owner,
                rs.getString(8), rs.getTimestamp(9).toInstant());
    }
}
