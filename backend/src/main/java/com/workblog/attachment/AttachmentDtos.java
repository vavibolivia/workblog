package com.workblog.attachment;

import java.time.Instant;

public final class AttachmentDtos {
    private AttachmentDtos() {}

    public record AttachmentResponse(String id, String name, String url, String contentType, long size, Attachment.Kind kind, Instant createdAt) {
        public static AttachmentResponse from(Attachment a) {
            return new AttachmentResponse(a.getPublicId(), a.getOriginalName(), a.url(), a.getContentType(), a.getSizeBytes(), a.getKind(), a.getCreatedAt());
        }
    }
}
