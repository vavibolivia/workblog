package com.workblog.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateRepository extends JpaRepository<PostTemplate, Long> {
    List<PostTemplate> findAllByOrderBySortOrderAscIdAsc();
}
