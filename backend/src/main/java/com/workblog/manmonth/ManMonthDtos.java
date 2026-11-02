package com.workblog.manmonth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ManMonthDtos {
    private ManMonthDtos() {}

    public record UserRef(Long id, String username, String displayName, String department) {}

    public record SheetSummary(Long id, String title, String description, LocalDate startDate, int weeks, boolean featured,
                               String ownerName, Instant updatedAt, boolean canEdit) {}

    public record ProjectDto(Long id, String name, int sortOrder) {}

    /** cells: 주 번호(0부터) → 값(보통 투입률, 아무 숫자). notes: 주 번호 → 메모(할 일). 없는 주는 빠진다. */
    public record RowDto(Long id, Long projectId, Long userId, String personName, String department, String role, int sortOrder,
                         Map<Integer, BigDecimal> cells, Map<Integer, String> notes) {}

    public record ChangeDto(String actorName, String summary, Instant createdAt) {}

    public record SheetDetail(Long id, String title, String description, LocalDate startDate, int weeks, boolean featured,
                              Long ownerId, String ownerName, Instant updatedAt, boolean canEdit, boolean canManage,
                              List<UserRef> editors, List<ProjectDto> projects, List<RowDto> rows, List<ChangeDto> changes,
                              List<EventDto> events) {}

    /** 이벤트 종류(화면 색). */
    public enum EventKind { MILESTONE, MEETING, RELEASE, REVIEW, HOLIDAY, LEAVE, ETC }

    /** 시기별 이벤트(공용). endDate 가 없으면 하루짜리. related(예: AirDeck)가 이름에 든 프로젝트 줄에도 표시. */
    public record EventDto(Long id, LocalDate startDate, LocalDate endDate, EventKind kind, String title, String related, String note,
                           String createdBy, Instant updatedAt) {}

    public record EventRequest(@NotNull LocalDate startDate, LocalDate endDate, EventKind kind, @NotBlank @Size(max = 200) String title,
                               @Size(max = 100) String related, @Size(max = 2000) String note) {}

    /** 이벤트 목록 + 이 사람이 고칠 수 있는지(관리자, 또는 공수 표 하나라도 주인·편집자). */
    public record EventList(List<EventDto> events, boolean canEdit) {}

    public record CreateSheetRequest(@NotBlank @Size(max = 200) String title, @Size(max = 1000) String description,
                                     @NotNull LocalDate startDate, @Min(1) @Max(104) int weeks) {}

    /** 준 항목만 바뀐다. startDate 를 바꾸면 기존 값은 날짜 기준으로 유지된다(범위 밖은 지워짐). */
    public record UpdateSheetRequest(@Size(max = 200) String title, @Size(max = 1000) String description, LocalDate startDate,
                                     @Min(1) @Max(104) Integer weeks) {}

    public record EditorsRequest(@NotNull List<Long> userIds) {}

    public record ProjectRequest(@NotBlank @Size(max = 100) String name, Integer sortOrder) {}

    /** userId 를 주면 이름·소속을 계정에서 채운다(personName · department 를 함께 주면 그 값이 우선). */
    public record RowRequest(Long projectId, Long userId, @Size(max = 100) String personName, @Size(max = 100) String department,
                             @Size(max = 100) String role, Integer sortOrder) {}

    /** value 가 null 이거나 0 이면 그 칸을 비운다. */
    public record CellChange(@NotNull Long rowId, @NotNull Integer week, BigDecimal value) {}

    public record CellsRequest(@NotNull @Size(max = 5000) List<CellChange> changes) {}

    /** text 가 비었으면 그 칸 메모를 지운다. */
    public record NoteChange(@NotNull Long rowId, @NotNull Integer week, @Size(max = 2000) String text) {}

    public record NotesRequest(@NotNull @Size(max = 5000) List<NoteChange> changes) {}

    /** applied=false 면 미리보기(아무것도 바뀌지 않음). cellsSet = 값이 바뀌는 칸, cellsCleared = 비워지는 칸. */
    public record ImportResult(boolean applied, int projectsCreated, int rowsCreated, int cellsSet, int cellsCleared, List<String> warnings) {}
}
