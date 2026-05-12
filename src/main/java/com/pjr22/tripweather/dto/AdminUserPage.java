package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated wrapper for {@link AdminUserSummary} rows. Returned by
 * {@code GET /api/admin/users}. Shape mirrors {@link AdminRoutePage} so the
 * admin SPA can share pagination rendering.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserPage {

    private List<AdminUserSummary> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
