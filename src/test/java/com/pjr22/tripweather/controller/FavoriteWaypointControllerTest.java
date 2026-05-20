package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.CreateFavoriteRequest;
import com.pjr22.tripweather.dto.FavoriteWaypointDto;
import com.pjr22.tripweather.dto.RenameFavoriteRequest;
import com.pjr22.tripweather.service.FavoriteWaypointService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoriteWaypointController}. Mirrors
 * {@link AdminRouteControllerTest}: pass-through coverage with Mockito,
 * no Spring context. Authorisation (the {@code authenticated()} matcher in
 * SecurityConfig) is enforced by the filter chain, not the controller, so
 * the 401-anonymous case is covered by SecurityConfig wiring + manual
 * {@code curl} smoke per the Phase 1 ships-when gate.
 *
 * <p>Status-mapped exceptions ({@code @ResponseStatus} on
 * FavoriteNotFoundException etc.) propagate through the controller
 * untouched — Spring's exception handler picks up the annotation when
 * dispatched through DispatcherServlet. Here we just verify the controller
 * doesn't swallow them.
 */
@ExtendWith(MockitoExtension.class)
class FavoriteWaypointControllerTest {

    @Mock private FavoriteWaypointService service;

    @InjectMocks
    private FavoriteWaypointController controller;

    private static FavoriteWaypointDto sampleDto(String label) {
        return new FavoriteWaypointDto(
                UUID.randomUUID(), label, "1234 Elm St", 40.0, -105.0, 1655.0,
                ZonedDateTime.now());
    }

    // ------------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------------

    @Test
    void list_passesSearchParamThroughToService() {
        List<FavoriteWaypointDto> expected = List.of(sampleDto("Home"), sampleDto("Work"));
        when(service.listForCurrentUser("ho")).thenReturn(expected);

        List<FavoriteWaypointDto> result = controller.list("ho");

        assertThat(result).isSameAs(expected);
        verify(service).listForCurrentUser("ho");
    }

    @Test
    void list_nullSearchParam_isPassedThroughVerbatim() {
        when(service.listForCurrentUser(null)).thenReturn(List.of());

        List<FavoriteWaypointDto> result = controller.list(null);

        assertThat(result).isEmpty();
        verify(service).listForCurrentUser(null);
    }

    // ------------------------------------------------------------------------
    // check
    // ------------------------------------------------------------------------

    @Test
    void check_returns200WithDtoWhenServiceFindsMatch() {
        FavoriteWaypointDto hit = sampleDto("Home");
        when(service.findAt(40.0, -105.0, "1234 Elm St")).thenReturn(Optional.of(hit));

        ResponseEntity<FavoriteWaypointDto> response = controller.check(40.0, -105.0, "1234 Elm St");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(hit);
    }

    @Test
    void check_returns204WhenServiceFindsNothing() {
        when(service.findAt(40.0, -105.0, "")).thenReturn(Optional.empty());

        ResponseEntity<FavoriteWaypointDto> response = controller.check(40.0, -105.0, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    // ------------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------------

    @Test
    void create_returns201WithDto() {
        FavoriteWaypointDto saved = sampleDto("Home");
        CreateFavoriteRequest req = new CreateFavoriteRequest(
                "Home", "1234 Elm St", 40.0, -105.0, 1655.0);
        when(service.create(req)).thenReturn(saved);

        ResponseEntity<FavoriteWaypointDto> response = controller.create(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(saved);
    }

    @Test
    void create_propagatesDuplicateLabelException() {
        CreateFavoriteRequest req = new CreateFavoriteRequest(
                "Home", "1234 Elm St", 40.0, -105.0, null);
        when(service.create(req)).thenThrow(
                new FavoriteWaypointService.DuplicateFavoriteLabelException("dup"));

        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(FavoriteWaypointService.DuplicateFavoriteLabelException.class);
    }

    @Test
    void create_propagatesInvalidFavoriteException() {
        CreateFavoriteRequest req = new CreateFavoriteRequest(
                "", "1234 Elm St", 40.0, -105.0, null);
        when(service.create(req)).thenThrow(
                new FavoriteWaypointService.InvalidFavoriteException("bad"));

        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(FavoriteWaypointService.InvalidFavoriteException.class);
    }

    // ------------------------------------------------------------------------
    // rename
    // ------------------------------------------------------------------------

    @Test
    void rename_passesIdAndBodyThroughToService() {
        UUID id = UUID.randomUUID();
        RenameFavoriteRequest req = new RenameFavoriteRequest("Casa");
        FavoriteWaypointDto renamed = sampleDto("Casa");
        when(service.rename(id, req)).thenReturn(renamed);

        FavoriteWaypointDto result = controller.rename(id, req);

        assertThat(result).isSameAs(renamed);
        verify(service).rename(id, req);
    }

    @Test
    void rename_propagatesNotFoundException() {
        UUID id = UUID.randomUUID();
        RenameFavoriteRequest req = new RenameFavoriteRequest("Casa");
        when(service.rename(id, req)).thenThrow(
                new FavoriteWaypointService.FavoriteNotFoundException("nope"));

        assertThatThrownBy(() -> controller.rename(id, req))
                .isInstanceOf(FavoriteWaypointService.FavoriteNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // delete
    // ------------------------------------------------------------------------

    @Test
    void delete_returns204AndDelegates() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).softDelete(id);
    }

    @Test
    void delete_propagatesNotFoundException() {
        UUID id = UUID.randomUUID();
        doThrow(new FavoriteWaypointService.FavoriteNotFoundException("nope"))
                .when(service).softDelete(id);

        assertThatThrownBy(() -> controller.delete(id))
                .isInstanceOf(FavoriteWaypointService.FavoriteNotFoundException.class);
    }
}
