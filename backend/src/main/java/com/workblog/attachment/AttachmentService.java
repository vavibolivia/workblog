package com.workblog.attachment;

import com.workblog.attachment.AttachmentDtos.AttachmentResponse;
import com.workblog.common.ApiException;
import com.workblog.config.WorkblogProperties;
import com.workblog.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 파일 저장소: {storage.dir}/{yyyy}/{MM}/{publicId}.{ext}. 글과의 연결은 글 저장 시점에 publicId 목록으로. */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/bmp");
    private static final Set<String> EXCEL_EXT = Set.of("xlsx", "xlsm", "xls", "csv");
    private static final Set<String> BLOCKED_EXT = Set.of("exe", "bat", "cmd", "com", "msi", "sh", "ps1", "js", "jar", "dll", "scr", "vbs", "html", "htm");

    private final AttachmentRepository repo;
    private final Path root;
    private final long maxBytes;

    public AttachmentService(AttachmentRepository repo, WorkblogProperties props) {
        this.repo = repo;
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        this.maxBytes = props.storage().maxFileMb() * 1024L * 1024L;
        try { Files.createDirectories(root); } catch (IOException e) { throw new IllegalStateException("저장소 폴더를 만들 수 없습니다: " + root, e); }
        log.info("첨부 저장소: {}", root);
    }

    @Transactional
    public AttachmentResponse store(MultipartFile file, User uploader) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("파일이 비어 있습니다.");
        try (var in = file.getInputStream()) {
            return AttachmentResponse.from(save(in, file.getSize(), file.getOriginalFilename(), file.getContentType(), uploader));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
        }
    }

    /** 변환 중 꺼낸 사진(워드·HTML 의 내장 이미지)이나 가져온 원본 파일을 저장한다. */
    @Transactional
    public Attachment storeBytes(byte[] data, String name, String contentType, User uploader) {
        if (data == null || data.length == 0) throw ApiException.badRequest("파일이 비어 있습니다.");
        try (var in = new java.io.ByteArrayInputStream(data)) {
            return save(in, data.length, name, contentType, uploader);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
        }
    }

    /** 가져오기 원본(.html 등)처럼 일반 업로드에서는 막는 확장자도 첨부로 남길 때: 내려받기 전용(attachment)으로만 제공된다. */
    @Transactional
    public Attachment storeOriginal(byte[] data, String name, String contentType, User uploader) {
        String original = sanitizeName(name);
        String ext = extension(original);
        if (Set.of("html", "htm").contains(ext)) {
            // 브라우저가 열지 않도록 text/plain 으로 저장(다운로드 시 원래 이름 유지)
            return persist(new java.io.ByteArrayInputStream(data), data.length, original, ext, "text/plain;charset=UTF-8", Attachment.Kind.FILE, uploader);
        }
        return storeBytes(data, name, contentType, uploader);
    }

    private Attachment save(java.io.InputStream in, long size, String name, String type, User uploader) throws IOException {
        if (size > maxBytes) throw ApiException.badRequest("파일이 너무 큽니다. 최대 " + (maxBytes / 1024 / 1024) + "MB 까지 올릴 수 있습니다.");
        String original = sanitizeName(name);
        String ext = extension(original);
        if (BLOCKED_EXT.contains(ext)) throw ApiException.badRequest("실행 파일이나 스크립트(." + ext + ")는 올릴 수 없습니다.");
        String contentType = type == null || type.isBlank() ? "application/octet-stream" : type;
        Attachment.Kind kind = IMAGE_TYPES.contains(contentType) || Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp").contains(ext) ? Attachment.Kind.IMAGE
                : EXCEL_EXT.contains(ext) ? Attachment.Kind.EXCEL : Attachment.Kind.FILE;
        if ((kind == Attachment.Kind.IMAGE || contentType.contains("svg")) && ("svg".equals(ext) || contentType.contains("svg"))) {
            throw ApiException.badRequest("SVG 는 스크립트를 담을 수 있어 올릴 수 없습니다. PNG 나 JPG 로 올려 주세요.");
        }
        return persist(in, size, original, ext, contentType, kind, uploader);
    }

    private Attachment persist(java.io.InputStream in, long size, String original, String ext, String contentType, Attachment.Kind kind, User uploader) {
        String publicId = UUID.randomUUID().toString();
        String stored = publicId + (ext.isEmpty() ? "" : "." + ext);
        java.time.LocalDate now = java.time.LocalDate.now();
        Path dir = root.resolve(String.valueOf(now.getYear())).resolve(String.format("%02d", now.getMonthValue()));
        try {
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
        }
        String rel = now.getYear() + "/" + String.format("%02d", now.getMonthValue()) + "/" + stored;
        return repo.save(new Attachment(publicId, uploader, original, rel, contentType, size, kind));
    }

    /** 파일 내용(내보내기에서 사진을 문서에 넣을 때). 없으면 null. */
    public byte[] readBytes(String publicId) {
        try {
            Attachment a = repo.findByPublicId(publicId).orElse(null);
            if (a == null) return null;
            Path p = root.resolve(a.getStoredName()).normalize();
            if (!p.startsWith(root) || !Files.exists(p)) return null;
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Attachment get(String publicId) {
        return repo.findByPublicId(publicId).orElseThrow(() -> ApiException.notFound("파일"));
    }

    public Resource resource(Attachment a) {
        Path p = root.resolve(a.getStoredName()).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw ApiException.notFound("파일");
        return new FileSystemResource(p);
    }

    /**
     * 글 저장 시: 요청에 담긴 publicId 들을 이 글에 연결(업로더 본인 또는 관리자).
     * 목록을 보냈으면(비어 있어도) 목록에 없는 기존 첨부는 글에서 뗀다(에디터의 '첨부 해제'). null 이면 그대로 둔다(PATCH).
     */
    @Transactional
    public void linkToPost(Long postId, List<String> publicIds, User actor) {
        if (publicIds == null) return;
        java.util.Set<String> keep = new java.util.HashSet<>(publicIds);
        for (Attachment a : repo.findByPostId(postId)) {
            if (!keep.contains(a.getPublicId())) a.detach();
        }
        if (publicIds.isEmpty()) return;
        for (Attachment a : repo.findByPublicIdIn(publicIds)) {
            boolean mine = a.getUploader().getId().equals(actor.getId()) || actor.isAdmin();
            if (mine && (a.getPostId() == null || a.getPostId().equals(postId))) a.attachTo(postId);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> forPost(Long postId) {
        return repo.findByPostIdOrderByCreatedAtAsc(postId).stream().map(AttachmentResponse::from).toList();
    }

    /** 글 영구 삭제 시: 글의 첨부 파일과 레코드를 지운다. */
    @Transactional
    public void purgeForPost(Long postId) {
        for (Attachment a : repo.findByPostId(postId)) {
            try { Files.deleteIfExists(root.resolve(a.getStoredName()).normalize()); } catch (IOException e) { log.warn("첨부 파일 삭제 실패: {}", a.getStoredName()); }
            repo.delete(a);
        }
    }

    /** 사용자 영구 삭제 시: 글에 연결되지 않은 업로드를 지운다(연결된 것은 글과 함께 지워진다). */
    @Transactional
    public void purgeOrphansForUser(Long userId) {
        for (Attachment a : repo.findByUploaderId(userId)) {
            if (a.getPostId() != null) continue;
            try { Files.deleteIfExists(root.resolve(a.getStoredName()).normalize()); } catch (IOException e) { log.warn("첨부 파일 삭제 실패: {}", a.getStoredName()); }
            repo.delete(a);
        }
    }

    private static String sanitizeName(String name) {
        String n = (name == null ? "file" : name).replace("\\", "/");
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        n = n.replaceAll("[\\p{Cntrl}]", "");
        if (n.isEmpty()) n = "file";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
