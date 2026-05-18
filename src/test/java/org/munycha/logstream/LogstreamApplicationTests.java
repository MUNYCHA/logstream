package org.munycha.logstream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.kafka.listener.auto-startup=false",
				"spring.kafka.consumer.group-id=test-group",
				"logstream.topics=test-topic",
				"logstream.allowed-origins=http://localhost",
				// NimbusJwtDecoder fetches JWKS lazily — a placeholder URI lets the context bootstrap
				"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks"
		}
)
class LogstreamApplicationTests {

	@Test
	void contextLoads() {
	}

}
