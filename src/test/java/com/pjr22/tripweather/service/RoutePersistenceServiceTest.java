package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.RouteSummaryDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.FavoriteWaypoint;
import com.pjr22.tripweather.model.Route;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.model.Waypoint;
import com.pjr22.tripweather.repository.FavoriteWaypointRepository;
import com.pjr22.tripweather.repository.RouteRepository;
import com.pjr22.tripweather.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Unit tests for the favorite-id population that Phase 3a adds to
 * {@link RoutePersistenceService#loadRoute(UUID)}.
 *
 * <p>Match semantics are pinned to exact equality on the
 * {@code (latitude, longitude, locationName)} tuple per decision #8 of
 * FAVORITES_AND_ROUTE_MGMT.md — a different decimal or a near-identical
 * address string means no match. Anonymous viewers see every id null,
 * and the lookup is against the <em>viewer's</em> favorites, not the
 * route owner's.
 */
@ExtendWith(MockitoExtension.class)
class RoutePersistenceServiceTest {

    @Mock private RouteRepository routeRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private FavoriteWaypointRepository favoriteWaypointRepository;

    @InjectMocks
    private RoutePersistenceService service;

    @Test
    void loadRoute_anonymousViewer_leavesEveryFavoriteIdNull() {
        UUID routeId = UUID.randomUUID();
        Route route = routeWithWaypoints(
                waypoint(40.0186, -105.2773, "1500 Pearl St, Boulder, CO"),
                waypoint(39.9319, -105.2941, "Eldorado Springs Trail"));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(currentUserService.currentUser()).thenReturn(Optional.empty());

        RouteDto dto = service.loadRoute(routeId);

        assertThat(dto.getWaypoints()).hasSize(2);
        assertThat(dto.getWaypoints()).allSatisfy(w ->
                assertThat(w.getFavoriteId()).isNull());
        // No reason to hit the favorites repo when the viewer is anonymous.
        verify(favoriteWaypointRepository, never()).findAllByUser(any(UUID.class));
    }

    @Test
    void loadRoute_authViewerWithNoFavorites_leavesEveryFavoriteIdNull() {
        UUID routeId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        Route route = routeWithWaypoints(
                waypoint(40.0, -105.0, "Somewhere"));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(currentUserService.currentUser()).thenReturn(Optional.of(userWithId(viewerId)));
        when(favoriteWaypointRepository.findAllByUser(viewerId)).thenReturn(List.of());

        RouteDto dto = service.loadRoute(routeId);

        assertThat(dto.getWaypoints()).singleElement()
                .satisfies(w -> assertThat(w.getFavoriteId()).isNull());
    }

    @Test
    void loadRoute_populatesFavoriteIdForMatchingTupleOnly() {
        UUID routeId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID favoriteId = UUID.randomUUID();
        Route route = routeWithWaypoints(
                waypoint(40.0186, -105.2773, "1500 Pearl St, Boulder, CO"),
                waypoint(39.9319, -105.2941, "Eldorado Springs Trail"),
                waypoint(40.3772, -105.5217, "Estes Park"));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(currentUserService.currentUser()).thenReturn(Optional.of(userWithId(viewerId)));
        when(favoriteWaypointRepository.findAllByUser(viewerId)).thenReturn(List.of(
                favorite(favoriteId, 40.0186, -105.2773, "1500 Pearl St, Boulder, CO", "Home")
        ));

        RouteDto dto = service.loadRoute(routeId);

        assertThat(dto.getWaypoints().get(0).getFavoriteId()).isEqualTo(favoriteId);
        assertThat(dto.getWaypoints().get(1).getFavoriteId()).isNull();
        assertThat(dto.getWaypoints().get(2).getFavoriteId()).isNull();
    }

    @Test
    void loadRoute_matchesAtTier1_regardlessOfLocationName() {
        // Tier 1: within 10 m → match no matter what the locationName says.
        // Absorbs GPS jitter when a current-location waypoint was favorited
        // earlier and the saved route's coords were a few meters off.
        UUID routeId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID favoriteId = UUID.randomUUID();
        Route route = routeWithWaypoints(
                waypoint(40.0, -105.0, "1500 Pearl St, Boulder CO")); // missing comma
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(currentUserService.currentUser()).thenReturn(Optional.of(userWithId(viewerId)));
        when(favoriteWaypointRepository.findAllByUser(viewerId)).thenReturn(List.of(
                // 0.00005° ≈ 5.5 m latitude offset, different (canonical) name —
                // still within tier 1.
                favorite(favoriteId, 40.00005, -105.0, "1500 Pearl St, Boulder, CO", "Home")
        ));

        RouteDto dto = service.loadRoute(routeId);

        assertThat(dto.getWaypoints()).singleElement()
                .satisfies(w -> assertThat(w.getFavoriteId()).isEqualTo(favoriteId));
    }

    @Test
    void loadRoute_outsideTier2_doesNotMatch() {
        // 0.001° latitude ≈ 111 m — beyond the 50 m tier-2 radius, so even
        // an exact name match doesn't count.
        UUID routeId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID favoriteId = UUID.randomUUID();
        Route route = routeWithWaypoints(
                waypoint(40.0, -105.0, "1500 Pearl St, Boulder, CO"));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(currentUserService.currentUser()).thenReturn(Optional.of(userWithId(viewerId)));
        when(favoriteWaypointRepository.findAllByUser(viewerId)).thenReturn(List.of(
                favorite(favoriteId, 40.001, -105.0, "1500 Pearl St, Boulder, CO", "Home")
        ));

        RouteDto dto = service.loadRoute(routeId);

        assertThat(dto.getWaypoints()).singleElement()
                .satisfies(w -> assertThat(w.getFavoriteId()).isNull());
    }

    @Test
    void loadRoute_routeOwnerFavoritesAreIgnored_onlyViewerFavoritesCount() {
        // The route owner has a favorite at one of the waypoints; the viewer
        // (different user) does not. The viewer should see favoriteId=null
        // because the favorites repo is only ever queried for the viewer's id.
        UUID routeId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        // The route owner's id is implicit in the Route entity but never passed
        // to the favorites repo — the mock is keyed on viewerId, so any other
        // id returns an empty list (the Mockito default).
        Route route = routeWithWaypoints(
                waypoint(40.0186, -105.2773, "1500 Pearl St, Boulder, CO"));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(currentUserService.currentUser()).thenReturn(Optional.of(userWithId(viewerId)));
        when(favoriteWaypointRepository.findAllByUser(viewerId)).thenReturn(List.of());

        RouteDto dto = service.loadRoute(routeId);

        assertThat(dto.getWaypoints()).singleElement()
                .satisfies(w -> assertThat(w.getFavoriteId()).isNull());
        verify(favoriteWaypointRepository).findAllByUser(viewerId);
    }

    // ---- helpers ----

    private static User userWithId(UUID id) {
        User u = new User();
        u.setId(id);
        u.setName("test");
        u.setEmail("test@example.com");
        u.setEnabled(true);
        return u;
    }

    private static Route routeWithWaypoints(Waypoint... waypoints) {
        Route r = new Route();
        r.setId(UUID.randomUUID());
        r.setName("Test route");
        r.setUser(userWithId(UUID.randomUUID())); // route owner != viewer in tests above
        for (int i = 0; i < waypoints.length; i++) {
            waypoints[i].setSequence(i + 1);
            waypoints[i].setRoute(r);
        }
        r.setWaypoints(new java.util.ArrayList<>(List.of(waypoints)));
        return r;
    }

    private static Waypoint waypoint(double lat, double lon, String locationName) {
        Waypoint w = new Waypoint();
        w.setId(UUID.randomUUID());
        w.setLatitude(lat);
        w.setLongitude(lon);
        w.setLocationName(locationName);
        return w;
    }

    private static FavoriteWaypoint favorite(UUID id, double lat, double lon, String locationName, String label) {
        FavoriteWaypoint f = new FavoriteWaypoint();
        f.setId(id);
        f.setLatitude(lat);
        f.setLongitude(lon);
        f.setLocationName(locationName);
        f.setLabel(label);
        return f;
    }

    // ========================================================================
    // Phase 4 — listRoutes + renameRoute
    // ========================================================================

    @Test
    void listRoutes_blankSearch_callsFindSummariesByUser() {
        User viewer = userWithId(UUID.randomUUID());
        when(currentUserService.currentUserOrGuest()).thenReturn(viewer);
        RouteSummaryDto a = new RouteSummaryDto(UUID.randomUUID(), "A", java.time.ZonedDateTime.now(), 2L);
        RouteSummaryDto b = new RouteSummaryDto(UUID.randomUUID(), "B", java.time.ZonedDateTime.now(), 0L);
        when(routeRepository.findSummariesByUser(viewer.getId())).thenReturn(List.of(a, b));

        List<RouteSummaryDto> result = service.listRoutes(null);

        assertThat(result).containsExactly(a, b);
        verify(routeRepository, org.mockito.Mockito.never())
                .searchSummariesByUser(any(UUID.class), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void listRoutes_nonBlankSearch_callsSearchSummariesByUserWithTrimmedText() {
        User viewer = userWithId(UUID.randomUUID());
        when(currentUserService.currentUserOrGuest()).thenReturn(viewer);
        when(routeRepository.searchSummariesByUser(viewer.getId(), "weekend"))
                .thenReturn(List.of());

        service.listRoutes("  weekend  ");

        verify(routeRepository).searchSummariesByUser(viewer.getId(), "weekend");
        verify(routeRepository, org.mockito.Mockito.never()).findSummariesByUser(any(UUID.class));
    }

    @Test
    void renameRoute_updatesNameAndReturnsSummary() {
        User viewer = userWithId(UUID.randomUUID());
        when(currentUserService.currentUser()).thenReturn(Optional.of(viewer));

        Route route = new Route();
        UUID routeId = UUID.randomUUID();
        route.setId(routeId);
        route.setName("Old name");
        route.setUser(viewer);
        route.setCreated(java.time.ZonedDateTime.now());
        route.setWaypoints(new java.util.ArrayList<>(List.of(
                waypoint(40.0, -105.0, "x"), waypoint(40.1, -105.1, "y"))));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeRepository.save(any(Route.class))).thenAnswer(inv -> inv.getArgument(0));

        RouteSummaryDto result = service.renameRoute(routeId, "  New name  ");

        assertThat(result.id()).isEqualTo(routeId);
        assertThat(result.name()).isEqualTo("New name");
        assertThat(result.waypointCount()).isEqualTo(2L);
        assertThat(route.getName()).isEqualTo("New name");
    }

    @Test
    void renameRoute_blankName_throwsInvalid() {
        assertThatThrownBy(() -> service.renameRoute(UUID.randomUUID(), "   "))
                .isInstanceOf(RoutePersistenceService.InvalidRouteException.class)
                .hasMessageContaining("name");
        // Should not have touched the repo or the current-user resolver
        verify(routeRepository, org.mockito.Mockito.never()).findById(any(UUID.class));
        verify(currentUserService, org.mockito.Mockito.never()).currentUser();
    }

    @Test
    void renameRoute_tooLongName_throwsInvalid() {
        String tooLong = "a".repeat(256);
        assertThatThrownBy(() -> service.renameRoute(UUID.randomUUID(), tooLong))
                .isInstanceOf(RoutePersistenceService.InvalidRouteException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void renameRoute_anonymousCaller_throwsNotFound() {
        // Defensive: SecurityConfig blocks anonymous PATCH at the chain level,
        // but if it ever leaked through, the service surfaces 404 rather than
        // a misleading 500 / NPE.
        when(currentUserService.currentUser()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.renameRoute(UUID.randomUUID(), "Anything"))
                .isInstanceOf(RoutePersistenceService.RouteNotFoundException.class);
    }

    @Test
    void renameRoute_routeOwnedByOtherUser_throwsNotFound() {
        User viewer = userWithId(UUID.randomUUID());
        User owner = userWithId(UUID.randomUUID());
        when(currentUserService.currentUser()).thenReturn(Optional.of(viewer));

        Route someoneElsesRoute = new Route();
        UUID routeId = UUID.randomUUID();
        someoneElsesRoute.setId(routeId);
        someoneElsesRoute.setUser(owner);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(someoneElsesRoute));

        // 404 (not 403) so the response can't enumerate other users' routes —
        // same posture as deleteRoute.
        assertThatThrownBy(() -> service.renameRoute(routeId, "Hijacked"))
                .isInstanceOf(RoutePersistenceService.RouteNotFoundException.class);
        verify(routeRepository, org.mockito.Mockito.never()).save(any(Route.class));
    }

    @Test
    void renameRoute_routeNotFound_throwsNotFound() {
        User viewer = userWithId(UUID.randomUUID());
        when(currentUserService.currentUser()).thenReturn(Optional.of(viewer));
        UUID routeId = UUID.randomUUID();
        when(routeRepository.findById(routeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renameRoute(routeId, "Anything"))
                .isInstanceOf(RoutePersistenceService.RouteNotFoundException.class);
    }
}
