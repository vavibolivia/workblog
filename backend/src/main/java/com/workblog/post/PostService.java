package com.workblog.post;

import com.workblog.attachment.AttachmentService;
import com.workblog.category.Category;
import com.workblog.category.CategoryService;
import com.workblog.comment.CommentRepository;
import com.workblog.common.ApiException;
import com.workblog.common.PageResponse;
import com.workblog.post.PostDtos.*;
import com.workblog.reaction.ReactionService;
import com.workblog.reaction.ReactionService.Likes;
import com.workblog.revision.PostRevision;
import com.workblog.revision.PostRevisionRepository;
import com.workblog.revision.RevisionDtos.RevisionDetail;
import com.workblog.revision.RevisionDtos.RevisionSummary;
import com.workblog.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class PostService {
    private final PostRepository posts;
    private final PostRevisionRepository revisions;
    private final CategoryService categories;
    private final AttachmentService attachments;
    private final ReactionService reactions;
    private final CommentRepository comments;

    public PostService(PostRepository posts, PostRevisionRepository revisions, CategoryService categories,
                       AttachmentService attachments, ReactionService reactions, CommentRepository comments) {
        this.posts = posts;
        this.revisions = revisions;
        this.categories = categories;
        this.attachments = attachments;
        this.reactions = reactions;
        this.comments = comments;
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public PageResponse<PostSummary> search(String q, Long categoryId, String tag, Long authorId, Post.Status status,
                                            int page, int size, User viewer) {
        return search(q, categoryId, tag, authorId, status, null, page, size, viewer);
    }

    @Transactional(readOnly = true)
    public PageResponse<PostSummary> search(String q, Long categoryId, String tag, Long authorId, Post.Status status, Post.Sort sort,
                                            int page, int size, User viewer) {
        String query = q == null ? "" : q.trim();
        String catPath = categoryId == null ? "" : categories.get(categoryId).getPath();
        boolean showOthersDrafts = viewer.isAdmin() && (authorId != null || status == Post.Status.DRAFT);
        Page<Post> result = posts.search(query, catPath,
                tag == null ? "" : tag.trim(),
                authorId == null ? 0 : authorId,
                status == null ? "" : status.name(),
                viewer.getId(), showOthersDrafts,
                (sort == null ? Post.Sort.LATEST : sort).name(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        Likes likes = likesFor(result.getContent(), viewer);
        Map<Long, Long> cc = commentCounts(result.getContent());
        return PageResponse.of(result, p -> toSummary(p, query, likes, cc));
    }

    @Transactional
    public PostDetail getDetail(Long id, User viewer, boolean countView) {
        Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("글"));
        if (!canView(p, viewer)) throw ApiException.notFound("글");
        if (countView) p.incrementViews();
        return toDetail(p, viewer);
    }

    @Transactional(readOnly = true)
    public List<TagCount> tags(int limit) {
        Map<String, Long> counts = new HashMap<>();
        for (String tags : posts.allPublishedTags()) {
            for (String t : Post.splitTags(tags)) counts.merge(t, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).map(e -> new TagCount(e.getKey(), e.getValue())).toList();
    }

    @Transactional(readOnly = true)
    public List<PostSummary> deletedPosts(User viewer) {
        List<Post> mine = posts.findDeleted().stream()
                .filter(p -> viewer.isAdmin() || p.getAuthor().getId().equals(viewer.getId())).toList();
        Likes likes = likesFor(mine, viewer);
        Map<Long, Long> cc = commentCounts(mine);
        return mine.stream().map(p -> toSummary(p, "", likes, cc)).toList();
    }

    // ---------- commands ----------

    public PostDetail create(PostRequest req, User author) {
        Category cat = category(req.categoryId(), req.categoryPath());
        Post.ContentFormat fmt = req.contentFormat() == null ? Post.ContentFormat.HTML : req.contentFormat();
        Post p = new Post(req.title().trim(), body(req.content(), fmt), fmt, blankToNull(req.summary()), cat, req.tags(),
                req.status(), req.visibility(), author);
        applyPinned(p, req.pinned(), author);
        p = posts.save(p);
        attachments.linkToPost(p.getId(), req.attachmentIds(), author);
        revisions.save(PostRevision.snapshot(p, PostRevision.ChangeType.CREATE, req.changeNote(), author));
        return toDetail(p, author);
    }

    public PostDetail update(Long id, PostRequest req, User editor) {
        Post p = loadEditable(id, editor);
        Category cat = category(req.categoryId(), req.categoryPath());
        Post.ContentFormat fmt = req.contentFormat() == null ? Post.ContentFormat.HTML : req.contentFormat();
        p.applyEdit(req.title().trim(), body(req.content(), fmt), fmt, blankToNull(req.summary()), cat, req.tags(),
                req.status(), req.visibility());
        applyPinned(p, req.pinned(), editor);
        attachments.linkToPost(p.getId(), req.attachmentIds(), editor);
        revisions.save(PostRevision.snapshot(p, PostRevision.ChangeType.UPDATE, req.changeNote(), editor));
        return toDetail(p, editor);
    }

    /** 부분 수정: 보내지 않은 항목은 지금 값을 유지한다. 새 리비전이 하나 생긴다. */
    public PostDetail patch(Long id, PostPatchRequest req, User editor) {
        Post p = loadEditable(id, editor);
        String content = req.content() != null ? req.content() : p.getContent();
        if (content.isBlank()) throw ApiException.badRequest("content: 본문은 비울 수 없습니다.");
        String title = req.title() != null ? req.title() : p.getTitle();
        if (title.isBlank()) throw ApiException.badRequest("title: 제목은 비울 수 없습니다.");
        Post.ContentFormat fmt = req.contentFormat() != null ? req.contentFormat()
                : (req.content() != null ? Post.ContentFormat.HTML : p.getContentFormat());
        Long catId = req.categoryId() != null || req.categoryPath() != null ? req.categoryId() : p.getCategory().getId();
        PostRequest full = new PostRequest(title, content, fmt, req.attachmentIds(),
                req.summary() != null ? req.summary() : p.getSummary(), catId,
                req.tags() != null ? req.tags() : p.getTagList(),
                req.status() != null ? req.status() : p.getStatus(),
                req.visibility() != null ? req.visibility() : p.getVisibility(),
                req.pinned(), req.changeNote(), req.categoryId() == null ? req.categoryPath() : null);
        return update(id, full, editor);
    }

    public void delete(Long id, User editor) {
        Post p = loadEditable(id, editor);
        p.softDelete();
        revisions.save(PostRevision.snapshot(p, PostRevision.ChangeType.DELETE, "삭제", editor));
    }

    /** 영구 삭제(관리자, 삭제한 글만): 리비전·댓글·공감은 DB cascade, 첨부는 파일까지 지운다. 되돌릴 수 없다. */
    public void purge(Long id, User actor) {
        if (!actor.isAdmin()) throw ApiException.forbidden("영구 삭제는 관리자만 할 수 있습니다.");
        Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("글"));
        if (!p.isDeleted()) throw ApiException.badRequest("먼저 삭제한 뒤(휴지통) 영구 삭제할 수 있습니다.");
        attachments.purgeForPost(id);
        posts.delete(p);
    }

    public PostDetail undelete(Long id, User editor) {
        Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("글"));
        if (!p.isDeleted()) throw ApiException.badRequest("삭제된 글이 아닙니다.");
        if (!canEdit(p, editor)) throw ApiException.forbidden("이 글을 복구할 권한이 없습니다.");
        p.restoreFromDelete();
        revisions.save(PostRevision.snapshot(p, PostRevision.ChangeType.UNDELETE, "삭제 취소", editor));
        return toDetail(p, editor);
    }

    @Transactional(readOnly = true)
    public List<RevisionSummary> revisions(Long postId, User viewer) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("글"));
        if (!canView(p, viewer) && !canEdit(p, viewer)) throw ApiException.notFound("글");
        return revisions.findByPostIdOrderByVersionDesc(postId).stream().map(RevisionSummary::from).toList();
    }

    @Transactional(readOnly = true)
    public RevisionDetail revision(Long postId, int version, User viewer) {
        Post p = posts.findById(postId).orElseThrow(() -> ApiException.notFound("글"));
        if (!canView(p, viewer) && !canEdit(p, viewer)) throw ApiException.notFound("글");
        return revisions.findByPostIdAndVersion(postId, version).map(RevisionDetail::from)
                .orElseThrow(() -> ApiException.notFound("리비전"));
    }

    /** 리비전의 카테고리가 아직 있으면 그대로, 지워졌으면 기본 카테고리로 복원. 공지 고정 여부는 복원 대상이 아니다. */
    public PostDetail restore(Long postId, int version, User editor) {
        Post p = loadEditable(postId, editor);
        PostRevision r = revisions.findByPostIdAndVersion(postId, version)
                .orElseThrow(() -> ApiException.notFound("리비전"));
        Category cat = categories.resolveForPost(categories.existsId(r.getCategoryId()) ? r.getCategoryId() : null);
        p.applyEdit(r.getTitle(), r.getContent(), r.getContentFormat(), r.getSummary(), cat, Post.splitTags(r.getTags()),
                r.getStatus(), r.getVisibility());
        revisions.save(PostRevision.snapshot(p, PostRevision.ChangeType.RESTORE, "v" + version + " 내용으로 복원", editor));
        return toDetail(p, editor);
    }

    // ---------- helpers ----------

    private Post loadEditable(Long id, User editor) {
        Post p = posts.findById(id).orElseThrow(() -> ApiException.notFound("글"));
        if (p.isDeleted()) throw ApiException.notFound("글");
        if (!canEdit(p, editor)) throw ApiException.forbidden("이 글을 수정할 권한이 없습니다.");
        return p;
    }

    private Category category(Long categoryId, String categoryPath) {
        if (categoryId == null && categoryPath != null && !categoryPath.isBlank()) return categories.findByPathText(categoryPath);
        return categories.resolveForPost(categoryId);
    }

    /** 공지 고정은 관리자만. 다른 사용자가 보낸 pinned 값은 조용히 무시한다. */
    private static void applyPinned(Post p, Boolean pinned, User editor) {
        if (pinned != null && editor.isAdmin()) p.setPinned(pinned);
    }

    /** 발행 + 전체 공개 글은 누구나, 임시 저장·비공개 글은 작성자와 관리자만. */
    public static boolean canView(Post p, User viewer) {
        if (p.isDeleted()) return viewer.isAdmin() || p.getAuthor().getId().equals(viewer.getId());
        return p.isOpen() || canEdit(p, viewer);
    }

    public static boolean canEdit(Post p, User viewer) {
        return viewer.isAdmin() || p.getAuthor().getId().equals(viewer.getId());
    }

    /** HTML 본문은 저장 전에 정화한다. 마크다운은 그대로. */
    private static String body(String content, Post.ContentFormat fmt) {
        return fmt == Post.ContentFormat.HTML ? HtmlSanitizer.clean(content) : content;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Likes likesFor(List<Post> list, User viewer) {
        return reactions.forPosts(list.stream().map(Post::getId).toList(), viewer);
    }

    /** 페이지 단위로 글별 댓글 수를 한 번에 센다(목록 카드·댓글순 정렬 근거). */
    private Map<Long, Long> commentCounts(List<Post> list) {
        if (list.isEmpty()) return Map.of();
        Map<Long, Long> m = new HashMap<>();
        for (Object[] row : comments.countVisibleByPostIds(list.stream().map(Post::getId).toList())) m.put((Long) row[0], (Long) row[1]);
        return m;
    }

    PostSummary toSummary(Post p, String q, Likes likes, Map<Long, Long> commentCounts) {
        String plain = p.getContentFormat() == Post.ContentFormat.HTML ? HtmlSanitizer.toPlainText(p.getContent()) : p.getContent();
        return new PostSummary(p.getId(), p.getTitle(), p.getSummary(), snippet(plain, q), thumbnail(p.getContent(), p.getContentFormat()),
                categories.ref(p.getCategory()), p.getTagList(), p.getStatus(), p.getVisibility(), p.isPinned(),
                PostDtos.author(p.getAuthor()), p.getVersion(), p.getViewCount(),
                likes.count(p.getId()), likes.liked(p.getId()), commentCounts.getOrDefault(p.getId(), 0L),
                p.getCreatedAt(), p.getUpdatedAt(), p.getPublishedAt());
    }

    PostDetail toDetail(Post p, User viewer) {
        Likes likes = likesFor(List.of(p), viewer);
        return new PostDetail(p.getId(), p.getTitle(), p.getContent(), p.getContentFormat(), p.getSummary(), categories.ref(p.getCategory()), p.getTagList(),
                p.getStatus(), p.getVisibility(), p.isPinned(), PostDtos.author(p.getAuthor()), p.getVersion(), p.getViewCount(),
                likes.count(p.getId()), likes.liked(p.getId()),
                p.getCreatedAt(), p.getUpdatedAt(), p.getPublishedAt(), p.getDeletedAt(), canEdit(p, viewer), attachments.forPost(p.getId()));
    }

    private static final Pattern MD_NOISE = Pattern.compile("(```[\\s\\S]*?```)|(^#+\\s*)|(\\*\\*|__|~~|`)|(!\\[[^\\]]*\\]\\([^)]*\\))|(\\[([^\\]]*)\\]\\([^)]*\\))", Pattern.MULTILINE);
    private static final Pattern HTML_IMG = Pattern.compile("<img\\b[^>]*?\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_IMG = Pattern.compile("!\\[[^\\]]*\\]\\(\\s*<?([^\\s)>]+)>?(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)");

    /** 본문의 첫 이미지 주소. HTML 은 첫 img src, 마크다운은 첫 ![](url). 없으면 null. */
    static String thumbnail(String content, Post.ContentFormat fmt) {
        if (content == null || content.isBlank()) return null;
        Matcher m = (fmt == Post.ContentFormat.MARKDOWN ? MD_IMG : HTML_IMG).matcher(content);
        if (!m.find()) return null;
        String url = m.group(1).trim();
        return url.isEmpty() ? null : url;
    }

    /** Plain-text excerpt around the first match of q (or the beginning when q is empty). */
    static String snippet(String content, String q) {
        if (content == null) return "";
        String plain = MD_NOISE.matcher(content).replaceAll(m -> m.group(6) != null ? Matcher.quoteReplacement(m.group(6)) : "")
                .replaceAll("\\s+", " ").trim();
        int window = 160;
        if (q == null || q.isBlank()) {
            return plain.length() <= window ? plain : plain.substring(0, window) + "…";
        }
        int idx = plain.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));
        if (idx < 0) return plain.length() <= window ? plain : plain.substring(0, window) + "…";
        int start = Math.max(0, idx - window / 3);
        int end = Math.min(plain.length(), start + window);
        return (start > 0 ? "…" : "") + plain.substring(start, end) + (end < plain.length() ? "…" : "");
    }
}
