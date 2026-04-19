package com.automation.dto.request;

import java.util.List;

public record ExportToPoolRequest(
        String nameEn,
        String nameDe,
        String descriptionEn,
        String descriptionDe,
        String category,
        List<String> tags,
        String author
) {}
