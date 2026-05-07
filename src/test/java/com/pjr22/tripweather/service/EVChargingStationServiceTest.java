package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.dto.EVChargingStationRequest;
import com.pjr22.tripweather.dto.EVChargingStationResponse;
import com.pjr22.tripweather.repository.EvStationQueryDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EVChargingStationServiceTest {

    private static final double DEFAULT_RADIUS_MILES = 1.0;
    private static final int    DEFAULT_LIMIT        = 200;
    private static final double METERS_PER_MILE      = 1609.344;

    /** A trimmed example of one feature's properties block, shaped like the
     *  NREL response in {@code examples/nrel_response.json}. */
    private static final String EXAMPLE_PROPERTIES_JSON = """
            {
              "id": 163553,
              "access_code": "public",
              "status_code": "E",
              "fuel_type_code": "ELEC",
              "station_name": "Love's 581 Salina, UT",
              "ev_network": "Electrify America",
              "ev_connector_types": ["CHADEMO", "J1772COMBO"],
              "ev_dc_fast_num": 4,
              "ev_level1_evse_num": null,
              "ev_level2_evse_num": null,
              "city": "Salina",
              "state": "UT"
            }
            """;

    @Mock private EvStationQueryDao queryDao;

    private EVChargingStationService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new EVChargingStationService(queryDao, objectMapper,
                DEFAULT_RADIUS_MILES, DEFAULT_LIMIT);
    }

    @Test
    void emptyRoute_shortCircuitsWithoutQuery() {
        EVChargingStationRequest req = new EVChargingStationRequest();
        req.setRoute(List.of());

        EVChargingStationResponse response = service.getStationsAlongRoute(req);

        assertThat(response.getType()).isEqualTo("FeatureCollection");
        assertThat(response.getFeatures()).isEmpty();
        verifyNoInteractions(queryDao);
    }

    @Test
    void frontendDefaultParams_mapToNrelEquivalentFilter() {
        // The defaults in EVChargingStationService.js: today these go straight to
        // NREL; under Phase 3 they have to produce identical predicates locally.
        Map<String, Object> params = frontendDefaultParams();
        when(queryDao.findAlongRoute(anyString(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());

        service.getStationsAlongRoute(requestWith(params));

        ArgumentCaptor<EvStationQueryDao.Filter> filter =
                ArgumentCaptor.forClass(EvStationQueryDao.Filter.class);
        ArgumentCaptor<Double> radius = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> wkt = ArgumentCaptor.forClass(String.class);

        org.mockito.Mockito.verify(queryDao).findAlongRoute(
                wkt.capture(), radius.capture(), filter.capture(), limit.capture());

        EvStationQueryDao.Filter f = filter.getValue();
        assertThat(f.fuelType()).isEqualTo("ELEC");
        assertThat(f.status()).isEqualTo("E");
        assertThat(f.access()).isEqualTo("public");
        assertThat(f.connectorTypes()).containsExactly("J1772COMBO");
        assertThat(f.requireDcFast()).isTrue();
        assertThat(f.requireLevel1()).isFalse();
        assertThat(f.requireLevel2()).isFalse();
        assertThat(f.networks()).isNull();   // frontend doesn't send ev_network

        // distance=1.0 miles → 1609.344 meters; limit=100 from the JS defaults.
        assertThat(radius.getValue()).isEqualTo(METERS_PER_MILE);
        assertThat(limit.getValue()).isEqualTo(100);

        assertThat(wkt.getValue()).startsWith("LINESTRING(").endsWith(")");
    }

    @Test
    void omittedParameters_produceNoClauseRatherThanFalseyValues() {
        // Mirrors NREL's omit-defaults: a missing parameter applies no filter.
        when(queryDao.findAlongRoute(anyString(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());

        service.getStationsAlongRoute(requestWith(Map.of()));

        ArgumentCaptor<EvStationQueryDao.Filter> filter =
                ArgumentCaptor.forClass(EvStationQueryDao.Filter.class);
        ArgumentCaptor<Double> radius = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);

        org.mockito.Mockito.verify(queryDao).findAlongRoute(
                anyString(), radius.capture(), filter.capture(), limit.capture());

        EvStationQueryDao.Filter f = filter.getValue();
        assertThat(f.fuelType()).isNull();
        assertThat(f.status()).isNull();
        assertThat(f.access()).isNull();
        assertThat(f.networks()).isNull();
        assertThat(f.connectorTypes()).isNull();
        assertThat(f.requireDcFast()).isFalse();
        assertThat(f.requireLevel1()).isFalse();
        assertThat(f.requireLevel2()).isFalse();

        // Defaults plumbed through.
        assertThat(radius.getValue()).isEqualTo(DEFAULT_RADIUS_MILES * METERS_PER_MILE);
        assertThat(limit.getValue()).isEqualTo(DEFAULT_LIMIT);
    }

    @Test
    void commaSeparatedConnectorTypes_splitIntoArrayMatchingNrelOrSemantics() {
        Map<String, Object> params = new HashMap<>();
        params.put("ev_connector_type", "J1772COMBO,CHADEMO");
        when(queryDao.findAlongRoute(anyString(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());

        service.getStationsAlongRoute(requestWith(params));

        ArgumentCaptor<EvStationQueryDao.Filter> filter =
                ArgumentCaptor.forClass(EvStationQueryDao.Filter.class);
        org.mockito.Mockito.verify(queryDao).findAlongRoute(
                anyString(), anyDouble(), filter.capture(), anyInt());
        assertThat(filter.getValue().connectorTypes())
                .containsExactly("J1772COMBO", "CHADEMO");
    }

    @Test
    void chargingLevelLevel2_requiresLevel2Count() {
        Map<String, Object> params = new HashMap<>();
        params.put("ev_charging_level", "2");
        when(queryDao.findAlongRoute(anyString(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());

        service.getStationsAlongRoute(requestWith(params));

        ArgumentCaptor<EvStationQueryDao.Filter> filter =
                ArgumentCaptor.forClass(EvStationQueryDao.Filter.class);
        org.mockito.Mockito.verify(queryDao).findAlongRoute(
                anyString(), anyDouble(), filter.capture(), anyInt());
        assertThat(filter.getValue().requireLevel2()).isTrue();
        assertThat(filter.getValue().requireDcFast()).isFalse();
    }

    @Test
    void resultRows_assembleIntoGeoJsonFeaturesWithUpstreamProperties() {
        when(queryDao.findAlongRoute(anyString(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of(new EvStationQueryDao.StationRow(
                        163553L, 38.93144, -111.85365, EXAMPLE_PROPERTIES_JSON)));

        EVChargingStationResponse response = service.getStationsAlongRoute(
                requestWith(frontendDefaultParams()));

        assertThat(response.getFeatures()).hasSize(1);
        EVChargingStationResponse.EVChargingStationFeature feature = response.getFeatures().get(0);
        assertThat(feature.getType()).isEqualTo("Feature");
        assertThat(feature.getGeometry().getType()).isEqualTo("Point");
        // GeoJSON convention: [lon, lat].
        assertThat(feature.getGeometry().getCoordinates())
                .containsExactly(-111.85365, 38.93144);
        assertThat(feature.getProperties().getId()).isEqualTo(163553);
        assertThat(feature.getProperties().getStation_name()).isEqualTo("Love's 581 Salina, UT");
        assertThat(feature.getProperties().getEv_connector_types())
                .containsExactly("CHADEMO", "J1772COMBO");
        assertThat(feature.getProperties().getEv_dc_fast_num()).isEqualTo(4);
    }

    private static Map<String, Object> frontendDefaultParams() {
        // Mirrors the defaultParams object in static/js/services/EVChargingStationService.js.
        Map<String, Object> p = new HashMap<>();
        p.put("format", "geojson");
        p.put("distance", 1.0);
        p.put("fuel_type", "ELEC");
        p.put("status", "E");
        p.put("access", "public");
        p.put("ev_charging_level", "dc_fast");
        p.put("ev_connector_type", "J1772COMBO");
        p.put("limit", 100);
        return p;
    }

    private static EVChargingStationRequest requestWith(Map<String, Object> params) {
        EVChargingStationRequest req = new EVChargingStationRequest();
        req.setRoute(List.of(
                List.of(-111.0, 38.5),
                List.of(-111.5, 38.7),
                List.of(-112.0, 39.0)));
        req.setParameters(params);
        return req;
    }
}
