package com.workblog.revision;

import com.workblog.post.Post;
import com.workblog.post.PostDtos.AuthorSummary;

import java.time.Instant;
import java.util.List;

public final class RevisionDtos {
    private RevisionDtos() {}

    public record RevisionSummary(Long id, Long postId, int version, String title, Post.Status status,
                                  PostRevision.ChangeType changeType, String changeNote,
                                  AuthorSummary editedBy, Instant createdAt) {
        public static RevisionSummary from(PostRevision r) {
            var e = r.getEditedBy();
            return new RevisionSummary(r.getId(), r.getPost().getId(), r.getVersion(), r.getTitle(), r.getStatus(),
                    r.getChangeType(), r.getChangeNote(),
                    new AuthorSummary(e.getId(), e.getUsername(), e.getDisplayName(), e.getDepartment()), r.getCreatedAt());
        }
    }

    public record RevisionDetail(Long id, Long postId, int version, String title, String content, Post.ContentFormat contentFormat, String summary,
                                 String category, List<String> tags, Post.Status status,
                                 PostRevision.ChangeType changeType, String changeNote,
                                 AuthorSummary editedBy, Instant createdAt) {
        public static RevisionDetail from(PostRevision r) {
            var e = r.getEditedBy();
            return new RevisionDetail(r.getId(), r.getPost().getId(), r.getVersion(), r.getTitle(), r.getContent(), r.getContentFormat(),
                    r.getSummary(), r.getCategory(), Post.splitTags(r.getTags()), r.getStatus(),
                    r.getChangeType(), r.getChangeNote(),
                    new AuthorSummary(e.getId(), e.getUsername(), e.getDisplayName(), e.getDepartment()), r.getCreatedAt());
        }
    }

    /** Activity feed entry (company-wide history). */
    public record ActivityEntry(Long revisionId, Long postId, String postTitle, int version,
                                PostRevision.ChangeType changeType, String changeNote,
                                AuthorSummary editedBy, Instant createdAt) {
        public static ActivityEntry from(PostRevision r) {
            var e = r.getEditedBy();
            return new ActivityEntry(r.getId(), r.getPost().getId(), r.getTitle(), r.getVersion(), r.getChangeType(),
                    r.getChangeNote(), new AuthorSummary(e.getId(), e.getUsername(), e.getDisplayName(), e.getDepartment()),
                    r.getCreatedAt());
        }
    }
}
