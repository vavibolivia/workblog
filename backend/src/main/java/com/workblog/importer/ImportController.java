package com.workblog.importer;

import com.workblog.attachment.Attachment;
import com.workblog.attachment.AttachmentDtos.AttachmentResponse;
import com.workblog.auth.CurrentUser;
import com.workblog.common.ApiException;
import com.workblog.importer.ImportService.Converted;
import com.workblog.post.Post;
import com.workblog.post.PostDtos.PostDetail;
import com.workblog.post.PostDtos.PostPatchRequest;
import com.workblog.post.PostDtos.PostRequest;
import com.workblog.post.PostService;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * 파일 가져오기 API: HTML·Word(.docx)·Excel(.xlsx/.xls)·CSV·Markdown·텍스트 파일을 글로 만든다.
 * <ul>
 *   <li>{@code POST /api/import/convert} — 변환만(저장 안 함). 에디터의 '파일 불러오기'가 쓴다.</li>
 *   <li>{@code POST /api/posts/import} — 파일로 새 글.</li>
 *   <li>{@code PUT /api/posts/{id}/import} — 파일 내용으로 기존 글 본문 교체(새 리비전).</li>
 * </ul>
 */
@Tag(name = "파일 가져오기", description = "HTML·Word·Excel·CSV·Markdown·텍스트 파일 → 글")
@RestController
@RequestMapping("/api")
public class ImportController {
    public record ConvertResponse(String title, String content, String contentFormat, ImportService.SourceType sourceType,
                                  List<AttachmentResponse> attachments, List<String> warnings) {}
    public record ImportResponse(PostDetail post, ImportService.SourceType sourceType, List<String> warnings) {}
    public record FormatsResponse(Set<String> extensions, long maxFileMb) {}

    private final ImportService importer;
    private final PostService posts;
    private final com.workblog.config.WorkblogProperties props;

    public ImportController(ImportService importer, PostService posts, com.workblog.config.WorkblogProperties props) {
        this.importer = importer;
        this.posts = posts;
        this.props = props;
    }

    @Operation(summary = "가져올 수 있는 형식")
    @GetMapping("/import/formats")
    public FormatsResponse formats() {
        return new FormatsResponse(ImportService.supportedExtensions(), props.storage().maxFileMb());
    }

    @Operation(summary = "파일 → 본문 변환(저장하지 않음)", description = "결과 content(HTML)를 POST /api/posts 에 그대로 쓸 수 있다. 문서 안의 사진은 첨부로 저장되고 본문에 주소가 들어간다.")
    @PostMapping(value = "/import/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConvertResponse convert(@RequestParam("file") MultipartFile file,
                                   @Parameter(description = "원본 파일도 첨부로 저장") @RequestParam(defaultValue = "false") boolean attachOriginal,
                                   @CurrentUser User user) {
        Converted c = importer.convert(bytes(file), file.getOriginalFilename(), file.getContentType(), attachOriginal, user);
        return new ConvertResponse(c.title(), c.html(), "HTML", c.sourceType(),
                c.attachments().stream().map(AttachmentResponse::from).toList(), c.warnings());
    }

    @Operation(summary = "파일로 새 글", description = "제목을 주지 않으면 문서 제목(HTML <title>, 첫 제목 줄) 또는 파일 이름을 쓴다. tags 는 쉼표로 구분.")
    @PostMapping(value = "/posts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResponse create(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) Long categoryId,
                                 @Parameter(description = "카테고리 이름 경로. 예) 서버 정보 > AWS") @RequestParam(required = false) String categoryPath,
                                 @RequestParam(required = false) String tags,
                                 @RequestParam(required = false) String summary,
                                 @RequestParam(defaultValue = "PUBLISHED") Post.Status status,
                                 @RequestParam(defaultValue = "PUBLIC") Post.Visibility visibility,
                                 @RequestParam(defaultValue = "true") boolean attachOriginal,
                                 @CurrentUser User user) {
        Converted c = importer.convert(bytes(file), file.getOriginalFilename(), file.getContentType(), attachOriginal, user);
        String t = title != null && !title.isBlank() ? title.trim() : c.title();
        PostRequest req = new PostRequest(limit(t, 300), c.html(), Post.ContentFormat.HTML, ids(c), summary, categoryId,
                splitTags(tags), status, visibility, null, "파일 가져오기: " + limit(Objects.toString(file.getOriginalFilename(), ""), 400),
                categoryPath);
        return new ImportResponse(posts.create(req, user), c.sourceType(), c.warnings());
    }

    @Operation(summary = "파일로 기존 글 본문 교체", description = "본문만 파일 내용으로 바뀌고 카테고리·태그·공개 범위는 유지된다(title 을 주면 제목도). 이전 내용은 변경 이력에 남는다.")
    @PutMapping(value = "/posts/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse replace(@PathVariable Long id,
                                  @RequestParam("file") MultipartFile file,
                                  @RequestParam(required = false) String title,
                                  @RequestParam(required = false) String changeNote,
                                  @RequestParam(defaultValue = "true") boolean attachOriginal,
                                  @CurrentUser User user) {
        // 권한 확인을 변환(첨부 저장)보다 먼저
        PostDetail current = posts.getDetail(id, user, false);
        if (!current.editable() || current.deletedAt() != null) throw ApiException.forbidden("이 글을 수정할 권한이 없습니다.");
        Converted c = importer.convert(bytes(file), file.getOriginalFilename(), file.getContentType(), attachOriginal, user);
        String note = changeNote != null && !changeNote.isBlank() ? changeNote.trim()
                : "파일로 본문 교체: " + limit(Objects.toString(file.getOriginalFilename(), ""), 400);
        // 기존 첨부는 유지하고 변환으로 생긴 첨부(사진·원본)를 더한다
        List<String> keep = new ArrayList<>(current.attachments().stream().map(AttachmentResponse::id).toList());
        keep.addAll(ids(c));
        PostPatchRequest req = new PostPatchRequest(title == null || title.isBlank() ? null : limit(title.trim(), 300), c.html(),
                Post.ContentFormat.HTML, keep, null, null, null, null, null, null, null, limit(note, 500));
        return new ImportResponse(posts.patch(id, req, user), c.sourceType(), c.warnings());
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("파일이 비어 있습니다.");
        try { return file.getBytes(); } catch (IOException e) { throw ApiException.badRequest("파일을 읽지 못했습니다."); }
    }

    private static List<String> ids(Converted c) {
        return c.attachments().stream().map(Attachment::getPublicId).toList();
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String limit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
