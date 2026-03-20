package org.munycha.logstream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.kafka.listener.auto-startup=false",
				"spring.kafka.consumer.group-id=test-group",
				"logstream.topics=test-topic",
				"logstream.allowed-origins=http://localhost"
		}
)
class LogstreamApplicationTests {

	@Test
	void contextLoads() {
	}

}
