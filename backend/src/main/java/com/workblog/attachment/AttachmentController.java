package com.workblog.attachment;

import com.workblog.attachment.AttachmentDtos.AttachmentResponse;
import com.workblog.auth.CurrentUser;
import com.workblog.user.User;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/files")
public class AttachmentController {
    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    /** 에디터에서 사진·엑셀·일반 파일 업로드. 응답의 url 을 본문(<img src>)이나 첨부 목록에 쓴다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@RequestParam("file") MultipartFile file, @CurrentUser User uploader) {
        return service.store(file, uploader);
    }

    /** 파일 내려받기/표시. 이미지 태그가 쓰므로 토큰 없이 열린다(URL 은 추측 불가한 UUID). */
    @GetMapping("/{publicId}")
    public ResponseEntity<Resource> download(@PathVariable String publicId, @RequestParam(defaultValue = "false") boolean download) {
        Attachment a = service.get(publicId);
        Resource res = service.resource(a);
        ContentDisposition cd = ContentDisposition.builder(download || a.getKind() != Attachment.Kind.IMAGE ? "attachment" : "inline")
                .filename(a.getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(res);
    }
}
