package com.smarthotel.gateway;

import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="smarthotel.internal.token=transport-test-token-at-least-32-characters")
class UpstreamFailureTest {
    static final int CLOSED_PORT = closedPort();
    @LocalServerPort int port;
    static int closedPort() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            int port = server.getAddress().getPort();
            server.stop(0);
            return port;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("smarthotel.services.hotel-uri", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }
    @Test void disconnectedUpstreamReturnsSafe503() {
        var response = WebClient.create("http://127.0.0.1:" + port).get().uri("/api/v1/room-types")
                .exchangeToMono(result -> {
                    assertThat(result.statusCode().value()).isEqualTo(503);
                    return result.bodyToMono(String.class);
                }).block(Duration.ofSeconds(8));
        assertThat(response).contains("\"code\":503").doesNotContain("Exception", "127.0.0.1");
    }
}
