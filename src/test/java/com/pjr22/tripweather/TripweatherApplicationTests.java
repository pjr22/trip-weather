package com.pjr22.tripweather;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.password=tripdb",
        "trip.email.enabled=false",
        "trip.auth.remember-me.enabled=false",
        "trip.admin.enabled=false"
})
class TripweatherApplicationTests {

	@Test
	void contextLoads() {
	}

}
