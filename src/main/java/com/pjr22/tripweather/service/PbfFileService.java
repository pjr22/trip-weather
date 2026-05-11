package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.PbfFileCreateRequest;
import com.pjr22.tripweather.dto.PbfFileDto;
import com.pjr22.tripweather.dto.PbfFileUpdateRequest;
import com.pjr22.tripweather.model.PbfFile;
import com.pjr22.tripweather.repository.PbfFileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Backs {@link com.pjr22.tripweather.controller.AdminPbfController}.
 * Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>Mostly thin CRUD against {@link PbfFileRepository} with one
 * domain-shaped operation — {@link #checkUpstreamNow(String)} — that
 * fetches the Geofabrik {@code .md5} sibling of a pbf's URL and writes
 * the freshness columns. This mirrors what the host cron does on its
 * own schedule but is initiated synchronously from the admin console
 * and deliberately leaves {@code next_check_at} alone so the cron's
 * automatic cadence is unaffected.
 *
 * <p>The {@link #retryStuckApply(String)} method recovers a row whose
 * {@code last_apply_started_at} is set but {@code last_apply_finished_at}
 * is null — i.e. a previous host-side apply was interrupted (cron
 * killed, host rebooted, etc). The 4 h stale-detection window on the
 * cron eventually does the same thing automatically; this just lets
 * the operator skip the wait.
 */
@Service
@Slf4j
public class PbfFileService {

    /**
     * Wider RestClient than the existing geofabrikRestClient because pbf
     * rows can point at any Geofabrik path — eastern US, Canada, Europe,
     * etc. — not just the {@code /north-america/us} base the coverage
     * loader uses. Each fetch passes an absolute URI; we don't share the
     * base-bound bean.
     */
    private final RestClient absoluteRestClient = RestClient.builder().build();

    private final PbfFileRepository repository;

    public PbfFileService(PbfFileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PbfFileDto> listAll() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getPbfName().compareTo(b.getPbfName()))
                .map(PbfFileDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PbfFileDto> findOne(String pbfName) {
        return repository.findById(pbfName).map(PbfFileDto::from);
    }

    @Transactional
    public PbfFileDto create(PbfFileCreateRequest request) {
        if (repository.existsById(request.getPbfName())) {
            throw new PbfFileAlreadyExistsException(request.getPbfName());
        }
        PbfFile entity = new PbfFile();
        entity.setPbfName(request.getPbfName());
        entity.setGeofabrikUrl(request.getGeofabrikUrl());
        entity.setActive(request.getActive() == null ? true : request.getActive());
        entity.setCheckIntervalDays(
                request.getCheckIntervalDays() == null ? 7 : request.getCheckIntervalDays());
        entity.setUpdateIntervalDays(request.getUpdateIntervalDays());
        entity.setNextCheckAt(request.getNextCheckAt());
        entity.setNextUpdateAt(request.getNextUpdateAt());
        return PbfFileDto.from(repository.save(entity));
    }

    @Transactional
    public PbfFileDto update(String pbfName, PbfFileUpdateRequest request) {
        PbfFile entity = repository.findById(pbfName)
                .orElseThrow(() -> new PbfFileNotFoundException(pbfName));
        if (request.getGeofabrikUrl() != null) {
            entity.setGeofabrikUrl(request.getGeofabrikUrl());
        }
        if (request.getActive() != null) {
            entity.setActive(request.getActive());
        }
        if (request.getCheckIntervalDays() != null) {
            entity.setCheckIntervalDays(request.getCheckIntervalDays());
        }
        if (request.getUpdateIntervalDays() != null) {
            entity.setUpdateIntervalDays(request.getUpdateIntervalDays());
        }
        if (request.getNextCheckAt() != null) {
            entity.setNextCheckAt(request.getNextCheckAt());
        }
        if (request.getNextUpdateAt() != null) {
            entity.setNextUpdateAt(request.getNextUpdateAt());
        }
        return PbfFileDto.from(repository.save(entity));
    }

    @Transactional
    public void delete(String pbfName) {
        if (!repository.existsById(pbfName)) {
            throw new PbfFileNotFoundException(pbfName);
        }
        // routing_coverage.pbf_name rows pointing at this pbf go NULL via
        // the ON DELETE SET NULL constraint declared in the migration.
        repository.deleteById(pbfName);
    }

    /**
     * Set {@code next_update_at = now()} so the next cron tick picks the
     * row up. No-op if already scheduled in the past; we just overwrite.
     */
    @Transactional
    public PbfFileDto scheduleNow(String pbfName) {
        PbfFile entity = repository.findById(pbfName)
                .orElseThrow(() -> new PbfFileNotFoundException(pbfName));
        entity.setNextUpdateAt(ZonedDateTime.now());
        return PbfFileDto.from(repository.save(entity));
    }

    /**
     * Clear {@code last_apply_started_at} (so the row's no longer marked
     * "apply in flight") and set {@code next_update_at = now()}. The
     * caller is asserting that whatever previously claimed to be applying
     * is no longer running — usually because the cron crashed or the
     * host rebooted. The 4 h stale-detection on the cron would do the
     * same thing automatically; this lets the operator skip the wait.
     */
    @Transactional
    public PbfFileDto retryStuckApply(String pbfName) {
        PbfFile entity = repository.findById(pbfName)
                .orElseThrow(() -> new PbfFileNotFoundException(pbfName));
        entity.setLastApplyStartedAt(null);
        entity.setNextUpdateAt(ZonedDateTime.now());
        return PbfFileDto.from(repository.save(entity));
    }

    /**
     * JVM-side equivalent of the cron's cheap upstream check. Fetches the
     * Geofabrik {@code .md5} for this pbf, parses out the md5 hex, reads
     * the response's {@code Last-Modified} header, and writes those into
     * {@code last_check_at} + {@code last_remote_md5} +
     * {@code last_remote_modified}.
     *
     * <p>Deliberately does <b>not</b> touch {@code next_check_at} — manual
     * checks are independent of the cron's automatic schedule
     * (see ADMIN_CONSOLE.md Phase 2b).
     */
    @Transactional
    public PbfFileDto checkUpstreamNow(String pbfName) {
        PbfFile entity = repository.findById(pbfName)
                .orElseThrow(() -> new PbfFileNotFoundException(pbfName));

        String md5Url = entity.getGeofabrikUrl() + ".md5";
        log.info("Manual upstream md5 check for pbf '{}' at {}", pbfName, md5Url);

        ResponseEntity<String> response;
        try {
            response = absoluteRestClient.get()
                    .uri(URI.create(md5Url))
                    .retrieve()
                    .toEntity(String.class);
        } catch (Exception e) {
            throw new PbfUpstreamCheckException(
                    "Failed to fetch " + md5Url + ": " + e.getMessage(), e);
        }

        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new PbfUpstreamCheckException("Empty response from " + md5Url);
        }
        String md5 = body.trim().split("\\s+")[0];
        if (!md5.matches("[0-9a-fA-F]{32}")) {
            throw new PbfUpstreamCheckException(
                    "Response from " + md5Url + " did not start with a 32-char md5: "
                  + body.substring(0, Math.min(body.length(), 80)));
        }

        ZonedDateTime now = ZonedDateTime.now();
        entity.setLastCheckAt(now);
        entity.setLastRemoteMd5(md5.toLowerCase());

        // Last-Modified parse — best-effort; the cron also writes this so
        // if our parse fails we just leave the previous value alone rather
        // than throwing (the md5 comparison is the authoritative signal).
        String lastModified = response.getHeaders().getFirst("Last-Modified");
        if (lastModified != null) {
            try {
                Instant instant = DateTimeFormatter.RFC_1123_DATE_TIME
                        .parse(lastModified, Instant::from);
                entity.setLastRemoteModified(instant.atZone(ZoneOffset.UTC));
            } catch (Exception e) {
                log.warn("Could not parse Last-Modified '{}' from {}: {}",
                        lastModified, md5Url, e.getMessage());
            }
        }

        return PbfFileDto.from(repository.save(entity));
    }

    // -------------------------------------------------------------------------
    // Exceptions surfaced to the controller for clean HTTP-status mapping.
    // -------------------------------------------------------------------------

    public static class PbfFileNotFoundException extends RuntimeException {
        public PbfFileNotFoundException(String pbfName) {
            super("Pbf '" + pbfName + "' not found");
        }
    }

    public static class PbfFileAlreadyExistsException extends RuntimeException {
        public PbfFileAlreadyExistsException(String pbfName) {
            super("Pbf '" + pbfName + "' already exists");
        }
    }

    public static class PbfUpstreamCheckException extends RuntimeException {
        public PbfUpstreamCheckException(String message) { super(message); }
        public PbfUpstreamCheckException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
