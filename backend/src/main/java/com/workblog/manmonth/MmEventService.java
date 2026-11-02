package com.workblog.manmonth;

import com.workblog.common.ApiException;
import com.workblog.manmonth.ManMonthDtos.*;
import com.workblog.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 시기별 이벤트(공용 목록). 공수 표와 따로 관리하고, 각 공수 표는 자기 기간에 걸친 것만 보여 준다.
 * 보기는 로그인한 모두, 고치기는 관리자 또는 공수 표 하나라도 주인·편집자인 사람.
 */
@Service
public class MmEventService {
    private static final String COLS = "id, start_date, end_date, kind, title, related, note, created_by, updated_at";
    private final JdbcTemplate jdbc;

    public MmEventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** from~to(포함)에 하루라도 걸치는 이벤트. 둘 다 null 이면 전부. */
    static List<EventDto> between(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
        String where = from == null && to == null ? "" : " WHERE start_date <= ? AND COALESCE(end_date, start_date) >= ?";
        Object[] args = from == null && to == null ? new Object[0]
                : new Object[]{Date.valueOf(to == null ? LocalDate.of(9999, 12, 31) : to), Date.valueOf(from == null ? LocalDate.of(1, 1, 1) : from)};
        return jdbc.query("SELECT " + COLS + " FROM mm_events" + where + " ORDER BY start_date, end_date NULLS FIRST, id", (rs, i) -> {
            Date end = rs.getDate(3);
            return new EventDto(rs.getLong(1), rs.getDate(2).toLocalDate(), end == null ? null : end.toLocalDate(),
                    EventKind.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getTimestamp(9).toInstant());
        }, args);
    }

    public EventList list(LocalDate from, LocalDate to, User viewer) {
        return new EventList(between(jdbc, from, to), canEdit(viewer));
    }

    public EventDto create(EventRequest req, User actor) {
        requireEdit(actor);
        check(req);
        Long id = jdbc.queryForObject("INSERT INTO mm_events (start_date, end_date, kind, title, related, note, created_by) VALUES (?,?,?,?,?,?,?) RETURNING id",
                Long.class, Date.valueOf(req.startDate()), end(req), kind(req), req.title().trim(), blank(req.related()), blank(req.note()), actor.getDisplayName());
        return get(id);
    }

    public EventDto update(long id, EventRequest req, User actor) {
        requireEdit(actor);
        check(req);
        int n = jdbc.update("UPDATE mm_events SET start_date = ?, end_date = ?, kind = ?, title = ?, related = ?, note = ?, updated_at = now() WHERE id = ?",
                Date.valueOf(req.startDate()), end(req), kind(req), req.title().trim(), blank(req.related()), blank(req.note()), id);
        if (n == 0) throw ApiException.notFound("이벤트를 찾을 수 없습니다.");
        return get(id);
    }

    public void delete(long id, User actor) {
        requireEdit(actor);
        if (jdbc.update("DELETE FROM mm_events WHERE id = ?", id) == 0) throw ApiException.notFound("이벤트를 찾을 수 없습니다.");
    }

    boolean canEdit(User u) {
        if (u.isAdmin()) return true;
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM mm_sheets WHERE owner_id = ?) + (SELECT count(*) FROM mm_sheet_editors WHERE user_id = ?)",
                Integer.class, u.getId(), u.getId()) > 0;
    }

    private void requireEdit(User u) {
        if (!canEdit(u)) throw ApiException.forbidden("이벤트는 관리자나 공수 표의 주인·편집자가 고칠 수 있습니다.");
    }

    private EventDto get(long id) {
        return jdbc.query("SELECT " + COLS + " FROM mm_events WHERE id = ?", (rs, i) -> {
            Date end = rs.getDate(3);
            return new EventDto(rs.getLong(1), rs.getDate(2).toLocalDate(), end == null ? null : end.toLocalDate(),
                    EventKind.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getTimestamp(9).toInstant());
        }, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("이벤트를 찾을 수 없습니다."));
    }

    private static void check(EventRequest req) {
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) throw ApiException.badRequest("끝 날짜가 시작보다 빠릅니다.");
        if (req.endDate() != null && ChronoUnit.DAYS.between(req.startDate(), req.endDate()) > 366 * 2)
            throw ApiException.badRequest("이벤트 기간은 2년까지입니다.");
    }

    private static Date end(EventRequest req) {
        return req.endDate() == null || req.endDate().equals(req.startDate()) ? null : Date.valueOf(req.endDate());
    }

    private static String kind(EventRequest req) {
        return (req.kind() == null ? EventKind.ETC : req.kind()).name();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
