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
 * 공수(Man-month) 표. 가로 = 주(월요일 시작), 세로 = 프로젝트별 인원, 칸 = 그 주 값(보통 투입률 0~1.0, 아무 숫자) + 메모.
 * 조회는 로그인한 모두, 수정은 관리자 · 표 주인 · 편집자, 표 설정·편집자 지정·삭제는 관리자 · 주인. 대문 게시는 com.workblog.front.
 */
@Service
@Transactional
public class ManMonthService {
    static final BigDecimal LIMIT = new BigDecimal("10000000000");

    private final JdbcTemplate jdbc;

    public ManMonthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Sheet(long id, String title, String description, LocalDate startDate, int weeks, boolean featured, Long ownerId,
                         String ownerName, Instant updatedAt) {}

    // ---------- 조회 ----------

    @Transactional(readOnly = true)
    public List<SheetSummary> list(User viewer) {
        List<Sheet> sheets = jdbc.query(SHEET_SQL + " ORDER BY s.updated_at DESC",
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
        Map<Long, Map<Integer, String>> notes = new HashMap<>();
        jdbc.query("SELECT n.row_id, n.week, n.text FROM mm_notes n JOIN mm_rows r ON r.id = n.row_id WHERE r.sheet_id = ? AND n.week < ?", rs -> {
            notes.computeIfAbsent(rs.getLong(1), k -> new TreeMap<>()).put(rs.getInt(2), rs.getString(3));
        }, id, s.weeks());
        List<RowDto> rows = jdbc.query("SELECT id, project_id, user_id, person_name, department, role, sort_order FROM mm_rows WHERE sheet_id = ? ORDER BY sort_order, id",
                (rs, i) -> {
                    long rid = rs.getLong(1);
                    Long uid = rs.getLong(3);
                    if (rs.wasNull()) uid = null;
                    return new RowDto(rid, rs.getLong(2), uid, rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7),
                            cells.getOrDefault(rid, Map.of()), notes.getOrDefault(rid, Map.of()));
                }, id);
        List<ChangeDto> changes = jdbc.query("SELECT actor_name, summary, created_at FROM mm_changes WHERE sheet_id = ? ORDER BY created_at DESC, id DESC LIMIT 30",
                (rs, i) -> new ChangeDto(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), id);
        List<EventDto> events = MmEventService.between(jdbc, s.startDate(), s.startDate().plusWeeks(s.weeks()).minusDays(1), viewer);
        return new SheetDetail(s.id(), s.title(), s.description(), s.startDate(), s.weeks(), s.featured(), s.ownerId(), s.ownerName(), s.updatedAt(),
                canEdit(s, viewer), canManage(s, viewer), editors, projects, rows, changes, events);
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

    /**
     * 표 복사: 새 표(주인 = 복사한 사람)에 프로젝트·인원(순서·계정·소속·역할)·편집자를 옮긴다.
     * withValues 면 값·메모를 날짜 기준으로 옮기고(원본 시작 → 새 시작 차이만큼 주 번호 이동), 새 기간 밖은 버린다.
     */
    @Transactional
    public SheetDetail copy(long id, CopySheetRequest req, User actor) {
        Sheet src = load(id);
        SheetDetail made = create(new CreateSheetRequest(req.title(), src.description(), req.startDate(), req.weeks()), actor);
        long nid = made.id();
        int shift = (int) ChronoUnit.WEEKS.between(src.startDate(), made.startDate());
        Map<Long, Long> pmap = new HashMap<>();
        for (Map<String, Object> p : jdbc.queryForList("SELECT id, name, sort_order FROM mm_projects WHERE sheet_id = ? ORDER BY sort_order, id", id)) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("INSERT INTO mm_projects (sheet_id, name, sort_order) VALUES (?,?,?)", new String[]{"id"});
                ps.setLong(1, nid); ps.setString(2, (String) p.get("name")); ps.setInt(3, ((Number) p.get("sort_order")).intValue());
                return ps;
            }, kh);
            pmap.put(((Number) p.get("id")).longValue(), Objects.requireNonNull(kh.getKey()).longValue());
        }
        int rowsN = 0, cellsN = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, project_id, user_id, person_name, department, role, sort_order FROM mm_rows WHERE sheet_id = ? ORDER BY sort_order, id", id)) {
            Long np = pmap.get(((Number) r.get("project_id")).longValue());
            if (np == null) continue;
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("INSERT INTO mm_rows (sheet_id, project_id, user_id, person_name, department, role, sort_order) VALUES (?,?,?,?,?,?,?)", new String[]{"id"});
                ps.setLong(1, nid); ps.setLong(2, np); if (r.get("user_id") == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, ((Number) r.get("user_id")).longValue()); ps.setString(4, (String) r.get("person_name"));
                ps.setString(5, (String) r.get("department")); ps.setString(6, (String) r.get("role")); ps.setInt(7, ((Number) r.get("sort_order")).intValue());
                return ps;
            }, kh);
            long nr = Objects.requireNonNull(kh.getKey()).longValue();
            rowsN++;
            if (req.withValues()) {
                long or = ((Number) r.get("id")).longValue();
                cellsN += jdbc.update("INSERT INTO mm_cells (row_id, week, value) SELECT ?, week - ?, value FROM mm_cells WHERE row_id = ? AND week - ? >= 0 AND week - ? < ? AND week < ?",
                        nr, shift, or, shift, shift, made.weeks(), src.weeks());
                jdbc.update("INSERT INTO mm_notes (row_id, week, text, updated_by) SELECT ?, week - ?, text, updated_by FROM mm_notes WHERE row_id = ? AND week - ? >= 0 AND week - ? < ? AND week < ?",
                        nr, shift, or, shift, shift, made.weeks(), src.weeks());
            }
        }
        jdbc.update("INSERT INTO mm_sheet_editors (sheet_id, user_id) SELECT ?, user_id FROM mm_sheet_editors WHERE sheet_id = ?", nid, id);
        log(nid, actor, "「" + src.title() + "」에서 복사: 프로젝트 " + pmap.size() + " · 인원 " + rowsN + (req.withValues() ? " · 값 " + cellsN + "칸" : " · 값 없이"));
        return get(nid, actor);
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
            for (String t : List.of("mm_cells", "mm_notes")) {   // 메모도 값과 같이 날짜 기준으로
                jdbc.update("UPDATE " + t + " SET week = week + 100000 - ? WHERE row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", shift, id);
                jdbc.update("DELETE FROM " + t + " WHERE week < 100000 AND row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", id);
                jdbc.update("UPDATE " + t + " SET week = week - 100000 WHERE row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", id);
            }
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
            jdbc.update("DELETE FROM mm_notes WHERE week >= ? AND row_id IN (SELECT id FROM mm_rows WHERE sheet_id = ?)", weeks, id);
        }
        if (!what.isEmpty()) {
            touch(id);
            log(id, actor, "표 설정: " + String.join(" · ", what));
        }
        return get(id, actor);
    }

    public void delete(long id, User actor) {
        requireManage(load(id), actor);
        jdbc.update("DELETE FROM front_items WHERE kind = 'MM_SHEET' AND ref_id = ?", id);
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
        String old = requireProject(id, projectId);
        String name = req.name().trim();
        jdbc.update("UPDATE mm_projects SET name = ?, sort_order = COALESCE(?, sort_order) WHERE id = ?", name, req.sortOrder(), projectId);
        int events = 0, skipped = 0;
        if (!old.trim().equalsIgnoreCase(name)) {
            // 이벤트의 '관련' 은 프로젝트 이름(글자)으로 잇는다 → 옛 이름을 쓰던 이벤트도 새 이름으로.
            // 다른 표에 옛 이름 프로젝트가 아직 있으면 그쪽 연결이 끊기므로 그때는 그대로 둔다.
            // 이벤트 수정 권한(만든 사람·관리자, MmEventService)을 넘지 않게: 관리자는 모두, 아니면 내가 만든 이벤트만 바꾼다.
            Integer others = jdbc.queryForObject("SELECT count(*) FROM mm_projects WHERE lower(trim(name)) = lower(?)", Integer.class, old.trim());
            if (others == null || others == 0) {
                if (actor.isAdmin()) {
                    events = jdbc.update("UPDATE mm_events SET related = ?, updated_at = now() WHERE lower(trim(related)) = lower(?)", name, old.trim());
                } else {
                    events = jdbc.update("UPDATE mm_events SET related = ?, updated_at = now() WHERE lower(trim(related)) = lower(?) AND created_by_id = ?", name, old.trim(), actor.getId());
                    Integer left = jdbc.queryForObject("SELECT count(*) FROM mm_events WHERE lower(trim(related)) = lower(?)", Integer.class, old.trim());
                    skipped = left == null ? 0 : left;
                }
            }
        }
        touch(id);
        log(id, actor, old.equals(name) ? "프로젝트 수정: " + name : "프로젝트 이름: " + old + " → " + name
                + (events > 0 ? " (관련 이벤트 " + events + "건도 바꿈)" : "")
                + (skipped > 0 ? " (다른 사람이 만든 관련 이벤트 " + skipped + "건은 권한이 없어 옛 이름 그대로 — 만든 사람·관리자가 고쳐야 함)" : ""));
        return get(id, actor);
    }

    /** 프로젝트 순서 한 칸 옮기기(dir = -1 위로, 1 아래로). 순서를 1..n 으로 다시 매긴다. */
    public SheetDetail moveProject(long id, long projectId, int dir, User actor) {
        requireEdit(load(id), actor);
        String name = requireProject(id, projectId);
        List<Long> ids = new ArrayList<>(jdbc.queryForList("SELECT id FROM mm_projects WHERE sheet_id = ? ORDER BY sort_order, id", Long.class, id));
        int i = ids.indexOf(projectId), j = i + Integer.signum(dir);
        if (dir == 0 || j < 0 || j >= ids.size()) return get(id, actor);
        Collections.swap(ids, i, j);
        for (int k = 0; k < ids.size(); k++) jdbc.update("UPDATE mm_projects SET sort_order = ? WHERE id = ?", k + 1, ids.get(k));
        touch(id);
        log(id, actor, "프로젝트 순서: " + name + (dir < 0 ? " 위로" : " 아래로"));
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
        // 이름·소속·계정 연결은 '사람' 정보 → 이 표에서 같은 사람(같은 계정, 계정이 없으면 같은 이름)의 행 모두에 적용.
        // 프로젝트·역할·순서는 이 행만. (한 행만 고쳐 다른 프로젝트 행에 옛 이름이 남던 문제)
        List<Long> same = samePersonRows(id, rowId);
        for (Long rid : same)
            jdbc.update("UPDATE mm_rows SET user_id = ?, person_name = ?, department = ? WHERE id = ?", req.userId(), p[0], p[1], rid);
        jdbc.update("UPDATE mm_rows SET project_id = COALESCE(?, project_id), role = ?, sort_order = COALESCE(?, sort_order) WHERE id = ?",
                req.projectId(), blankToNull(req.role()), req.sortOrder(), rowId);
        touch(id);
        log(id, actor, "인원 수정: " + p[0] + (same.size() > 1 ? " (같은 사람 " + same.size() + "개 행)" : ""));
        return get(id, actor);
    }

    /** 이 행과 같은 사람의 행들(이 행 포함): 계정이 있으면 같은 계정, 없으면 계정 없는 같은 이름(대소문자·앞뒤 공백 무시). */
    List<Long> samePersonRows(long sheetId, long rowId) {
        Map<String, Object> r = jdbc.queryForMap("SELECT user_id, person_name FROM mm_rows WHERE id = ?", rowId);
        Object uid = r.get("user_id");
        return uid != null
                ? jdbc.queryForList("SELECT id FROM mm_rows WHERE sheet_id = ? AND user_id = ?", Long.class, sheetId, ((Number) uid).longValue())
                : jdbc.queryForList("SELECT id FROM mm_rows WHERE sheet_id = ? AND user_id IS NULL AND lower(trim(person_name)) = lower(trim(?))",
                        Long.class, sheetId, (String) r.get("person_name"));
    }

    /** 인원 순서 한 칸 옮기기 — 같은 프로젝트 안에서(dir = -1 위로, 1 아래로). 표 전체 행 순서를 1..n 으로 다시 매긴다. */
    public SheetDetail moveRow(long id, long rowId, int dir, User actor) {
        requireEdit(load(id), actor);
        String name = requireRow(id, rowId);
        List<long[]> rows = new ArrayList<>(jdbc.query("SELECT id, project_id FROM mm_rows WHERE sheet_id = ? ORDER BY sort_order, id",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, id));
        int i = -1;
        for (int k = 0; k < rows.size(); k++) if (rows.get(k)[0] == rowId) i = k;
        int j = -1, step = Integer.signum(dir);
        for (int k = i + step; step != 0 && k >= 0 && k < rows.size(); k += step) if (rows.get(k)[1] == rows.get(i)[1]) { j = k; break; }
        if (j < 0) return get(id, actor);
        Collections.swap(rows, i, j);
        for (int k = 0; k < rows.size(); k++) jdbc.update("UPDATE mm_rows SET sort_order = ? WHERE id = ?", k + 1, rows.get(k)[0]);
        touch(id);
        log(id, actor, "인원 순서: " + name + (dir < 0 ? " 위로" : " 아래로"));
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
        int set = 0, cleared = 0, skipped = 0;
        for (CellChange c : req.changes()) {
            // 그사이 다른 사람이 행을 지웠거나 기간을 줄였으면 그 칸만 건너뛴다(묶음 전체를 거절하면 나머지도 영영 저장되지 않음)
            if (!rowIds.contains(c.rowId()) || c.week() < 0 || c.week() >= s.weeks()) { skipped++; continue; }
            BigDecimal v = normalize(c.value());
            if (v == null) {
                cleared += jdbc.update("DELETE FROM mm_cells WHERE row_id = ? AND week = ?", c.rowId(), c.week());
            } else {
                upsert(c.rowId(), c.week(), v);
                set++;
            }
        }
        if (skipped == req.changes().size() && !req.changes().isEmpty())
            throw ApiException.badRequest("고친 칸이 모두 없어진 행이거나 표 기간 밖입니다(다른 사람이 표를 바꿨을 수 있음). 표를 다시 읽습니다.");
        touch(id);
        log(id, actor, "칸 수정: " + set + "칸" + (cleared > 0 ? " · 비움 " + cleared + "칸" : "") + (skipped > 0 ? " · 없어진 칸 " + skipped + "개 건너뜀" : ""));
        return get(id, actor);
    }

    /** 주별 메모(할 일). 여러 칸에 한 번에(같은 메모를 묶어서). 빈 글이면 지운다. */
    public SheetDetail setNotes(long id, NotesRequest req, User actor) {
        Sheet s = load(id);
        requireEdit(s, actor);
        Set<Long> rowIds = new HashSet<>(jdbc.queryForList("SELECT id FROM mm_rows WHERE sheet_id = ?", Long.class, id));
        int set = 0, cleared = 0;
        for (NoteChange c : req.changes()) {
            if (!rowIds.contains(c.rowId())) throw ApiException.badRequest("이 표의 행이 아닙니다: " + c.rowId());
            if (c.week() < 0 || c.week() >= s.weeks()) throw ApiException.badRequest("주 범위 밖입니다: " + c.week());
            String t = c.text() == null ? "" : c.text().strip();
            if (t.isEmpty()) {
                cleared += jdbc.update("DELETE FROM mm_notes WHERE row_id = ? AND week = ?", c.rowId(), c.week());
            } else {
                if (jdbc.update("UPDATE mm_notes SET text = ?, updated_by = ?, updated_at = now() WHERE row_id = ? AND week = ?", t, actor.getDisplayName(), c.rowId(), c.week()) == 0)
                    jdbc.update("INSERT INTO mm_notes (row_id, week, text, updated_by) VALUES (?,?,?,?)", c.rowId(), c.week(), t, actor.getDisplayName());
                set++;
            }
        }
        touch(id);
        log(id, actor, "메모: " + set + "칸" + (cleared > 0 ? " · 지움 " + cleared + "칸" : ""));
        return get(id, actor);
    }

    /** 엑셀 가져오기용: 변경 이력·전체 다시 읽기 없이 프로젝트를 넣고 id 를 돌려준다. */
    long insertProject(long sheetId, String name) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("INSERT INTO mm_projects (sheet_id, name, sort_order) " +
                    "VALUES (?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM mm_projects WHERE sheet_id = ?))", new String[]{"id"});
            ps.setLong(1, sheetId);
            ps.setString(2, name);
            ps.setLong(3, sheetId);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    /** 엑셀 가져오기용 행 추가(변경 이력·전체 다시 읽기 없음). */
    long insertRow(long sheetId, long projectId, Long userId, String name, String dept, String role) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("INSERT INTO mm_rows (sheet_id, project_id, user_id, person_name, department, role, sort_order) " +
                    "VALUES (?,?,?,?,?,?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM mm_rows WHERE sheet_id = ?))", new String[]{"id"});
            ps.setLong(1, sheetId);
            ps.setLong(2, projectId);
            if (userId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, userId);
            ps.setString(4, name);
            ps.setString(5, blankToNull(dept));
            ps.setString(6, blankToNull(role));
            ps.setLong(7, sheetId);
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    void upsert(long rowId, int week, BigDecimal v) {
        if (jdbc.update("UPDATE mm_cells SET value = ? WHERE row_id = ? AND week = ?", v, rowId, week) == 0) {
            jdbc.update("INSERT INTO mm_cells (row_id, week, value) VALUES (?,?,?)", rowId, week, v);
        }
    }

    /** 아무 숫자(보통 투입률 0~1.0), 소수 둘째 자리. 0 이나 null 은 '비움'. */
    static BigDecimal normalize(BigDecimal v) {
        if (v == null) return null;
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        if (r.signum() == 0) return null;
        if (r.abs().compareTo(LIMIT) >= 0) throw ApiException.badRequest("숫자가 너무 큽니다(최대 9,999,999,999.99): " + v.toPlainString());
        return r;
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

    /** [이름, 소속]. 계정을 연결하면 이름은 늘 계정 이름, 소속은 계정 부서(계정에 부서가 없을 때만 준 값) — 계정이 바뀌면 같이 바뀐다(UserService). */
    String[] person(RowRequest req) {
        String name = blankToNull(req.personName());
        String dept = blankToNull(req.department());
        if (req.userId() != null) {
            List<String[]> u = jdbc.query("SELECT display_name, department FROM users WHERE id = ?", (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, req.userId());
            if (u.isEmpty()) throw ApiException.badRequest("없는 사용자입니다: " + req.userId());
            name = u.get(0)[0];
            if (blankToNull(u.get(0)[1]) != null) dept = u.get(0)[1].trim();
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

    private static final String SHEET_SQL = "SELECT s.id, s.title, s.description, s.start_date, s.weeks, " +
            "EXISTS (SELECT 1 FROM front_items f WHERE f.kind = 'MM_SHEET' AND f.ref_id = s.id) AS featured, s.owner_id, u.display_name, s.updated_at " +
            "FROM mm_sheets s LEFT JOIN users u ON u.id = s.owner_id";

    private static Sheet sheet(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long owner = rs.getLong(7);
        if (rs.wasNull()) owner = null;
        return new Sheet(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getInt(5), rs.getBoolean(6), owner,
                rs.getString(8), rs.getTimestamp(9).toInstant());
    }
}
