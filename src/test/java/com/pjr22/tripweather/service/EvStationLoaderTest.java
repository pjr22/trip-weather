package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.repository.EvStationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class EvStationLoaderTest {

    private static final String API_KEY = "nrel-test-key";
    // Two distinct station ids so we can also assert deactivation runs.
    private static final String NREL_FEED_RESPONSE = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type":"Point","coordinates":[-111.85365, 38.93144]},
                  "properties": {
                    "id": 163553,
                    "fuel_type_code": "ELEC",
                    "status_code": "E",
                    "access_code": "public",
                    "ev_network": "Electrify America",
                    "ev_connector_types": ["CHADEMO", "J1772COMBO"],
                    "ev_dc_fast_num": 4,
                    "ev_level1_evse_num": null,
                    "ev_level2_evse_num": null,
                    "station_name": "Love's 581 Salina, UT"
                  }
                },
                {
                  "type": "Feature",
                  "geometry": {"type":"Point","coordinates":[-104.9903, 39.7392]},
                  "properties": {
                    "id": 200001,
                    "fuel_type_code": "ELEC",
                    "status_code": "T",
                    "access_code": "public",
                    "ev_network": "Tesla",
                    "ev_connector_types": ["TESLA"],
                    "ev_dc_fast_num": 8
                  }
                }
              ]
            }
            """;

    @Mock private EvStationRepository repository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TaskScheduler taskScheduler;

    private MockRestServiceServer mockServer;
    private RestClient restClient;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private EvStationLoader loader;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://developer.nrel.gov");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(Instant.parse("2030-04-01T04:00:00Z"), ZoneOffset.UTC);

        loader = new EvStationLoader(restClient, repository, jdbcTemplate, objectMapper,
                taskScheduler, fixedClock, API_KEY,
                /* loaderEnabled */ true,
                /* bootstrapOnEmpty */ true);
    }

    @Test
    void successfulLoad_upsertsAllStationsAndDeactivatesMissing() throws Exception {
        mockServer.expect(requestTo(
                "https://developer.nrel.gov/api/alt-fuel-stations/v1.json"
                        + "?fuel_type=ELEC&limit=all&api_key=" + API_KEY))
                .andRespond(withSuccess(NREL_FEED_RESPONSE, MediaType.APPLICATION_JSON));

        int loaded = loader.load();

        assertThat(loaded).isEqualTo(2);
        verify(jdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        // Deactivation pass uses the start-of-fetch timestamp as cutoff.
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbcTemplate).update(anyString(), cutoff.capture());
        assertThat(cutoff.getValue()).isNotNull();
        mockServer.verify();
    }

    @Test
    void runWithRetryOnFailure_schedulesOneHourRetryWhenLoadFails() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());

        loader.runWithRetryOnFailure();

        verify(taskScheduler).schedule(any(Runnable.class), eq(fixedClock.instant().plusSeconds(3600)));
        verify(jdbcTemplate, never()).update(anyString(), any(Timestamp.class));
        mockServer.verify();
    }

    @Test
    void runWithRetryOnFailure_successPath_doesNotScheduleRetry() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(NREL_FEED_RESPONSE, MediaType.APPLICATION_JSON));

        loader.runWithRetryOnFailure();

        verify(jdbcTemplate, times(1)).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        verify(jdbcTemplate, times(1)).update(anyString(), any(Timestamp.class));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        mockServer.verify();
    }

    @Test
    void loaderDisabled_scheduledLoadIsNoOp() {
        EvStationLoader disabled = new EvStationLoader(restClient, repository, jdbcTemplate,
                objectMapper, taskScheduler, fixedClock, API_KEY,
                /* loaderEnabled */ false,
                /* bootstrapOnEmpty */ true);

        disabled.scheduledLoad();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }
}
