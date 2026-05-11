package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the loader-list response
 * ({@code GET /api/admin/loaders}). Phase 2 of ADMIN_CONSOLE.md.
 *
 * <p>The {@code category} field groups loaders for the data view's three
 * cards: {@code "cleanup"} (route + email-token sweeps),
 * {@code "data"} (NREL EV mirror), {@code "coverage"} (per-region ORS
 * coverage). The frontend uses it to decide which card a loader belongs to.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoaderSummaryDto {

    private String name;
    private String category;
    private LoaderRunDto lastRun;
}
