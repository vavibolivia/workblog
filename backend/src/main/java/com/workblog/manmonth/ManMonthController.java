package com.workblog.manmonth;

import com.workblog.auth.CurrentUser;
import com.workblog.common.ApiException;
import com.workblog.manmonth.ManMonthDtos.*;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "공수", description = "Man-month 표: 가로 주(월요일 시작) · 세로 프로젝트별 인원 · 칸 = 주 값(보통 투입률, 아무 숫자) + 주별 메모. 조회는 모두, 수정은 편집자")
@RestController
@RequestMapping("/api/mm/sheets")
public class ManMonthController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ManMonthService service;
    private final ManMonthExcel excel;

    public ManMonthController(ManMonthService service, ManMonthExcel excel) {
        this.service = service;
        this.excel = excel;
    }

    @Operation(summary = "표 목록", description = "featured = 대문에 올라 있음")
    @GetMapping
    public List<SheetSummary> list(@CurrentUser User user) {
        return service.list(user);
    }

    @Operation(summary = "표 만들기", description = "startDate 는 그 주 월요일로 맞춘다. 만든 사람이 주인(편집·설정 가능)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SheetDetail create(@Valid @RequestBody CreateSheetRequest req, @CurrentUser User user) {
        return service.create(req, user);
    }

    @GetMapping("/{id}")
    public SheetDetail get(@PathVariable long id, @CurrentUser User user) {
        return service.get(id, user);
    }

    @Operation(summary = "표 설정", description = "주인·관리자. startDate 를 바꾸면 값은 날짜 기준으로 유지")
    @PatchMapping("/{id}")
    public SheetDetail update(@PathVariable long id, @Valid @RequestBody UpdateSheetRequest req, @CurrentUser User user) {
        return service.update(id, req, user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @CurrentUser User user) {
        service.delete(id, user);
    }

    @Operation(summary = "편집자 지정", description = "주인·관리자. 목록 전체를 바꾼다")
    @PutMapping("/{id}/editors")
    public SheetDetail editors(@PathVariable long id, @Valid @RequestBody EditorsRequest req, @CurrentUser User user) {
        return service.setEditors(id, req, user);
    }

    @PostMapping("/{id}/projects")
    public SheetDetail addProject(@PathVariable long id, @Valid @RequestBody ProjectRequest req, @CurrentUser User user) {
        return service.addProject(id, req, user);
    }

    @PutMapping("/{id}/projects/{projectId}")
    public SheetDetail updateProject(@PathVariable long id, @PathVariable long projectId, @Valid @RequestBody ProjectRequest req, @CurrentUser User user) {
        return service.updateProject(id, projectId, req, user);
    }

    @DeleteMapping("/{id}/projects/{projectId}")
    public SheetDetail deleteProject(@PathVariable long id, @PathVariable long projectId, @CurrentUser User user) {
        return service.deleteProject(id, projectId, user);
    }

    @Operation(summary = "인원 추가", description = "프로젝트에 계정(userId) 또는 이름(personName) 으로")
    @PostMapping("/{id}/rows")
    public SheetDetail addRow(@PathVariable long id, @Valid @RequestBody RowRequest req, @CurrentUser User user) {
        return service.addRow(id, req, user);
    }

    @PutMapping("/{id}/rows/{rowId}")
    public SheetDetail updateRow(@PathVariable long id, @PathVariable long rowId, @Valid @RequestBody RowRequest req, @CurrentUser User user) {
        return service.updateRow(id, rowId, req, user);
    }

    @DeleteMapping("/{id}/rows/{rowId}")
    public SheetDetail deleteRow(@PathVariable long id, @PathVariable long rowId, @CurrentUser User user) {
        return service.deleteRow(id, rowId, user);
    }

    @PutMapping("/{id}/notes")
    @Operation(summary = "주별 메모(할 일)", description = "편집자. {changes:[{rowId, week, text}]} — text 가 비면 지움. 여러 칸에 같은 메모를 한 번에")
    public SheetDetail notes(@PathVariable long id, @Valid @RequestBody NotesRequest req, @CurrentUser User user) {
        return service.setNotes(id, req, user);
    }

    @Operation(summary = "칸 수정(여러 칸)", description = "value 가 null 이나 0 이면 비움. 아무 숫자(소수 둘째 자리)")
    @PutMapping("/{id}/cells")
    public SheetDetail cells(@PathVariable long id, @Valid @RequestBody CellsRequest req, @CurrentUser User user) {
        return service.setCells(id, req, user);
    }

    @Operation(summary = "엑셀 내려받기", description = "template=true 면 같은 구조의 빈 양식")
    @GetMapping("/{id}/excel")
    public ResponseEntity<byte[]> export(@PathVariable long id, @RequestParam(defaultValue = "false") boolean template, @CurrentUser User user) {
        SheetDetail d = service.get(id, user);
        String name = (template ? "공수양식_" : "공수_") + d.title().replaceAll("[\\\\/:*?\"<>|]", "_") + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(excel.export(d, !template));
    }

    @Operation(summary = "엑셀 올리기", description = "편집자. dryRun=true 면 미리보기만. 파일에 있는 행만 바꾼다(프로젝트·인원이 없으면 만든다). keepBlank=true(기본) 면 빈 칸은 기존 값 유지")
    @PostMapping(value = "/{id}/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importExcel(@PathVariable long id, @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "false") boolean dryRun,
                                    @RequestParam(defaultValue = "true") boolean keepBlank, @CurrentUser User user) {
        if (file.isEmpty()) throw ApiException.badRequest("파일이 비어 있습니다.");
        try {
            return excel.importInto(id, file.getInputStream(), user, dryRun, keepBlank);
        } catch (IOException e) {
            throw ApiException.badRequest("파일을 읽지 못했습니다.");
        }
    }
}
