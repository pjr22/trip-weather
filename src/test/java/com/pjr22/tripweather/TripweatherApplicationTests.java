package com.pjr22.tripweather;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.password=tripdb")
class TripweatherApplicationTests {

	@Test
	void contextLoads() {
	}

}
