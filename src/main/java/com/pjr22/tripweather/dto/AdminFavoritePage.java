package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated wrapper for {@link AdminFavoriteSummary} rows. Returned by
 * {@code GET /api/admin/favorites}. Mirrors {@link AdminRoutePage} so the
 * admin SPA's pagination + filter machinery is shared verbatim between
 * the two views.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminFavoritePage {

    private List<AdminFavoriteSummary> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
