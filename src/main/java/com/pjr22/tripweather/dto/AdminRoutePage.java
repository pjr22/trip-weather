package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated wrapper for {@link AdminRouteSummary} rows. Returned by
 * {@code GET /api/admin/routes}. Shape is deliberately small and JSON-friendly:
 * {@code content} plus enough metadata for the admin SPA to render page controls.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminRoutePage {

    private List<AdminRouteSummary> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
