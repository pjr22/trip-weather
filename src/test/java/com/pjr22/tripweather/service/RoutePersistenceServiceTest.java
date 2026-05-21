package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.RouteDto;
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

}
