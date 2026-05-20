package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.CreateFavoriteRequest;
import com.pjr22.tripweather.dto.FavoriteWaypointDto;
import com.pjr22.tripweather.dto.RenameFavoriteRequest;
import com.pjr22.tripweather.model.FavoriteWaypoint;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.FavoriteWaypointRepository;
import com.pjr22.tripweather.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoriteWaypointService}. Phase 1 of
 * FAVORITES_AND_ROUTE_MGMT.md. Covers ownership rejection, duplicate-label
 * collisions, input validation, and the locationName fallback.
 *
 * <p>Pattern mirrors {@link AdminRouteServiceTest}: pure Mockito, no Spring
 * context. The security-chain enforcement of 401 for anonymous callers is
 * exercised by SecurityConfig + manual smoke per the Phase 1 ships-when
 * gate; here we cover the defensive {@code currentUser().orElseThrow(...)}
 * branch.
 */
@ExtendWith(MockitoExtension.class)
class FavoriteWaypointServiceTest {

    @Mock private FavoriteWaypointRepository favoriteRepository;
    @Mock private CurrentUserService currentUserService;

    @InjectMocks
    private FavoriteWaypointService service;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(UUID.randomUUID());
        alice.setEmail("alice@example.com");
        alice.setName("alice");
    }

    private void asAlice() {
        when(currentUserService.currentUser()).thenReturn(Optional.of(alice));
    }

    private void asAnonymous() {
        when(currentUserService.currentUser()).thenReturn(Optional.empty());
    }

    private static FavoriteWaypoint sampleEntity(User owner, String label) {
        FavoriteWaypoint f = new FavoriteWaypoint();
        f.setId(UUID.randomUUID());
        f.setUser(owner);
        f.setLabel(label);
        f.setLocationName("1234 Elm St, Boulder, CO");
        f.setLatitude(40.0150);
        f.setLongitude(-105.2705);
        f.setElevation(1655.0);
        f.setCreated(ZonedDateTime.now());
        return f;
    }

    // ------------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------------

    @Test
    void list_blankSearch_callsFindAllByUser() {
        asAlice();
        when(favoriteRepository.findAllByUser(alice.getId()))
                .thenReturn(List.of(sampleEntity(alice, "Home"), sampleEntity(alice, "Work")));

        List<FavoriteWaypointDto> dtos = service.listForCurrentUser("");

        assertThat(dtos).hasSize(2);
        assertThat(dtos).extracting(FavoriteWaypointDto::label).containsExactly("Home", "Work");
        verify(favoriteRepository, never()).searchByUser(any(), any());
    }

    @Test
    void list_nullSearch_callsFindAllByUser() {
        asAlice();
        when(favoriteRepository.findAllByUser(alice.getId())).thenReturn(List.of());

        assertThat(service.listForCurrentUser(null)).isEmpty();
        verify(favoriteRepository, never()).searchByUser(any(), any());
    }

    @Test
    void list_nonBlankSearch_callsSearchByUserWithTrimmedText() {
        asAlice();
        when(favoriteRepository.searchByUser(alice.getId(), "home"))
                .thenReturn(List.of(sampleEntity(alice, "Home")));

        List<FavoriteWaypointDto> dtos = service.listForCurrentUser("  home  ");

        assertThat(dtos).hasSize(1);
        verify(favoriteRepository).searchByUser(alice.getId(), "home");
        verify(favoriteRepository, never()).findAllByUser(any());
    }

    @Test
    void list_anonymous_throwsNotFound() {
        asAnonymous();
        assertThatThrownBy(() -> service.listForCurrentUser(null))
                .isInstanceOf(FavoriteWaypointService.FavoriteNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // findAt (check endpoint)
    // ------------------------------------------------------------------------

    @Test
    void findAt_returnsDtoWhenRepositoryFindsMatch() {
        asAlice();
        FavoriteWaypoint hit = sampleEntity(alice, "Home");
        when(favoriteRepository.findFirstByUserIdAndLatitudeAndLongitudeAndLocationName(
                alice.getId(), 40.0, -105.0, "x")).thenReturn(Optional.of(hit));

        Optional<FavoriteWaypointDto> dto = service.findAt(40.0, -105.0, "x");

        assertThat(dto).isPresent();
        assertThat(dto.get().label()).isEqualTo("Home");
    }

    @Test
    void findAt_returnsEmptyWhenRepositoryFindsNothing() {
        asAlice();
        when(favoriteRepository.findFirstByUserIdAndLatitudeAndLongitudeAndLocationName(
                alice.getId(), 40.0, -105.0, "")).thenReturn(Optional.empty());

        assertThat(service.findAt(40.0, -105.0, "")).isEmpty();
    }

    // ------------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------------

    @Test
    void create_persistsEntityWithCurrentUserAndTrimmedLabel() {
        asAlice();
        when(favoriteRepository.existsByUserIdAndLabelIgnoreCase(alice.getId(), "Home")).thenReturn(false);
        when(favoriteRepository.save(any(FavoriteWaypoint.class))).thenAnswer(inv -> {
            FavoriteWaypoint f = inv.getArgument(0);
            f.setId(UUID.randomUUID());
            f.setCreated(ZonedDateTime.now());
            return f;
        });

        FavoriteWaypointDto saved = service.create(new CreateFavoriteRequest(
                "  Home  ", "1234 Elm St", 40.0, -105.0, 1655.0));

        assertThat(saved.label()).isEqualTo("Home");
        assertThat(saved.locationName()).isEqualTo("1234 Elm St");
        assertThat(saved.latitude()).isEqualTo(40.0);
        assertThat(saved.longitude()).isEqualTo(-105.0);
        assertThat(saved.elevation()).isEqualTo(1655.0);

        ArgumentCaptor<FavoriteWaypoint> captor = ArgumentCaptor.forClass(FavoriteWaypoint.class);
        verify(favoriteRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(alice);
    }

    @Test
    void create_blankLocationName_fallsBackToCoordinateString() {
        asAlice();
        when(favoriteRepository.existsByUserIdAndLabelIgnoreCase(alice.getId(), "Home")).thenReturn(false);
        when(favoriteRepository.save(any(FavoriteWaypoint.class))).thenAnswer(inv -> inv.getArgument(0));

        FavoriteWaypointDto saved = service.create(new CreateFavoriteRequest(
                "Home", "   ", 39.74024, -105.02340, null));

        assertThat(saved.locationName()).isEqualTo("39.74024, -105.02340");
    }

    @Test
    void create_nullLocationName_fallsBackToCoordinateString() {
        asAlice();
        when(favoriteRepository.existsByUserIdAndLabelIgnoreCase(alice.getId(), "Home")).thenReturn(false);
        when(favoriteRepository.save(any(FavoriteWaypoint.class))).thenAnswer(inv -> inv.getArgument(0));

        FavoriteWaypointDto saved = service.create(new CreateFavoriteRequest(
                "Home", null, 1.0, 2.0, null));

        assertThat(saved.locationName()).isEqualTo("1.00000, 2.00000");
    }

    @Test
    void create_blankLabel_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service.create(new CreateFavoriteRequest(
                "  ", "anywhere", 40.0, -105.0, null)))
                .isInstanceOf(FavoriteWaypointService.InvalidFavoriteException.class)
                .hasMessageContaining("label");
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void create_missingLatitude_throwsInvalid() {
        asAlice();
        assertThatThrownBy(() -> service.create(new CreateFavoriteRequest(
                "Home", "anywhere", null, -105.0, null)))
                .isInstanceOf(FavoriteWaypointService.InvalidFavoriteException.class);
    }

    @Test
    void create_duplicateLabel_throwsConflict() {
        asAlice();
        when(favoriteRepository.existsByUserIdAndLabelIgnoreCase(alice.getId(), "Home")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateFavoriteRequest(
                "Home", "anywhere", 40.0, -105.0, null)))
                .isInstanceOf(FavoriteWaypointService.DuplicateFavoriteLabelException.class);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void create_anonymous_throwsNotFound() {
        asAnonymous();
        assertThatThrownBy(() -> service.create(new CreateFavoriteRequest(
                "Home", "anywhere", 40.0, -105.0, null)))
                .isInstanceOf(FavoriteWaypointService.FavoriteNotFoundException.class);
    }

    // ------------------------------------------------------------------------
    // rename
    // ------------------------------------------------------------------------

    @Test
    void rename_updatesLabelWhenNewLabelIsUnique() {
        asAlice();
        FavoriteWaypoint existing = sampleEntity(alice, "Home");
        when(favoriteRepository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));
        when(favoriteRepository.existsByUserIdAndLabelIgnoreCase(alice.getId(), "Casa")).thenReturn(false);
        when(favoriteRepository.save(existing)).thenReturn(existing);

        FavoriteWaypointDto renamed = service.rename(existing.getId(), new RenameFavoriteRequest("Casa"));

        assertThat(renamed.label()).isEqualTo("Casa");
        assertThat(existing.getLabel()).isEqualTo("Casa");
    }

    @Test
    void rename_caseOnlyChange_isAllowedAndSkipsCollisionCheck() {
        asAlice();
        FavoriteWaypoint existing = sampleEntity(alice, "home");
        when(favoriteRepository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));
        when(favoriteRepository.save(existing)).thenReturn(existing);

        FavoriteWaypointDto renamed = service.rename(existing.getId(), new RenameFavoriteRequest("Home"));

        assertThat(renamed.label()).isEqualTo("Home");
        // existsByUserIdAndLabelIgnoreCase should NOT have been called because
        // the new label is case-insensitive-equal to the existing one (it
        // would otherwise trivially collide with itself).
        verify(favoriteRepository, never()).existsByUserIdAndLabelIgnoreCase(any(), any());
    }

    @Test
    void rename_duplicateLabel_throwsConflict() {
        asAlice();
        FavoriteWaypoint existing = sampleEntity(alice, "Home");
        when(favoriteRepository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));
        when(favoriteRepository.existsByUserIdAndLabelIgnoreCase(alice.getId(), "Work")).thenReturn(true);

        assertThatThrownBy(() -> service.rename(existing.getId(), new RenameFavoriteRequest("Work")))
                .isInstanceOf(FavoriteWaypointService.DuplicateFavoriteLabelException.class);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void rename_unknownIdOrCrossUser_throwsNotFound() {
        asAlice();
        UUID id = UUID.randomUUID();
        when(favoriteRepository.findByIdAndUserId(id, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(id, new RenameFavoriteRequest("Whatever")))
                .isInstanceOf(FavoriteWaypointService.FavoriteNotFoundException.class);
    }

    @Test
    void rename_blankLabel_throwsInvalid() {
        asAlice();
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.rename(id, new RenameFavoriteRequest("  ")))
                .isInstanceOf(FavoriteWaypointService.InvalidFavoriteException.class);
        verify(favoriteRepository, never()).findByIdAndUserId(any(), any());
    }

    // ------------------------------------------------------------------------
    // softDelete
    // ------------------------------------------------------------------------

    @Test
    void softDelete_setsDeletedAtAndSaves() {
        asAlice();
        FavoriteWaypoint existing = sampleEntity(alice, "Home");
        when(favoriteRepository.findByIdAndUserId(existing.getId(), alice.getId()))
                .thenReturn(Optional.of(existing));
        when(favoriteRepository.save(existing)).thenReturn(existing);

        ZonedDateTime before = ZonedDateTime.now();
        service.softDelete(existing.getId());
        ZonedDateTime after = ZonedDateTime.now();

        assertThat(existing.getDeletedAt()).isNotNull();
        assertThat(existing.getDeletedAt()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        verify(favoriteRepository).save(existing);
    }

    @Test
    void softDelete_unknownIdOrCrossUser_throwsNotFound() {
        asAlice();
        UUID id = UUID.randomUUID();
        when(favoriteRepository.findByIdAndUserId(id, alice.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.softDelete(id))
                .isInstanceOf(FavoriteWaypointService.FavoriteNotFoundException.class);
        verify(favoriteRepository, never()).save(any());
    }
}
