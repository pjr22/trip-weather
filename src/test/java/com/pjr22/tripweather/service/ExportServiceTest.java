package com.pjr22.tripweather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExportServiceTest {

    @Test
    void sanitizeFilenameKeepsSafeChars() {
        assertEquals("Trip-2026.06_to_Bend", ExportService.sanitizeFilename("Trip-2026.06_to_Bend"));
    }

    @Test
    void sanitizeFilenameReplacesUnsafeChars() {
        assertEquals("Pacific_Coast_NorCal_SoCal", ExportService.sanitizeFilename("Pacific Coast / NorCal & SoCal"));
    }

    @Test
    void sanitizeFilenameCollapsesUnderscoreRuns() {
        assertEquals("a_b", ExportService.sanitizeFilename("a    b"));
    }

    @Test
    void sanitizeFilenameTrimsLeadingTrailingUnderscores() {
        assertEquals("trip", ExportService.sanitizeFilename("***trip***"));
    }

    @Test
    void sanitizeFilenameFallsBackForBlank() {
        assertEquals("route", ExportService.sanitizeFilename(""));
        assertEquals("route", ExportService.sanitizeFilename("   "));
        assertEquals("route", ExportService.sanitizeFilename(null));
    }

    @Test
    void sanitizeFilenameFallsBackWhenAllUnsafe() {
        assertEquals("route", ExportService.sanitizeFilename("///"));
    }
}
