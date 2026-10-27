package com.workblog.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    Optional<Attachment> findByPublicId(String publicId);
    List<Attachment> findByPostIdOrderByCreatedAtAsc(Long postId);
    List<Attachment> findByPublicIdIn(List<String> publicIds);
    List<Attachment> findByPostId(Long postId);
    List<Attachment> findByUploaderId(Long uploaderId);
}
