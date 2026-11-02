package com.workblog.manmonth;

import com.workblog.auth.CurrentUser;
import com.workblog.manmonth.ManMonthDtos.*;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "공수 이벤트", description = "시기별 이벤트(공용). 공수 표와 따로 관리, 각 공수 표는 자기 기간에 걸친 것을 보여 준다")
@RestController
@RequestMapping("/api/mm/events")
public class MmEventController {
    private final MmEventService service;

    public MmEventController(MmEventService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "이벤트 목록", description = "from·to(yyyy-mm-dd, 포함)에 걸치는 것. 비우면 전부. canEdit = 이 사람이 고칠 수 있는지")
    public EventList list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @CurrentUser User user) {
        return service.list(from, to, user);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "이벤트 추가", description = "관리자 또는 공수 표 주인·편집자. {startDate, endDate?, kind?, title, related?, note?}")
    public EventDto create(@Valid @RequestBody EventRequest req, @CurrentUser User user) {
        return service.create(req, user);
    }

    @PutMapping("/{id}")
    @Operation(summary = "이벤트 수정")
    public EventDto update(@PathVariable long id, @Valid @RequestBody EventRequest req, @CurrentUser User user) {
        return service.update(id, req, user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "이벤트 삭제")
    public void delete(@PathVariable long id, @CurrentUser User user) {
        service.delete(id, user);
    }
}
