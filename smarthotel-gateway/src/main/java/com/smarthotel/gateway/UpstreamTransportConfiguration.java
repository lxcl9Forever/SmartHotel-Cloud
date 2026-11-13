package com.smarthotel.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UpstreamTransportConfiguration {
    @Bean
    HttpClientCustomizer boundedUpstreamTransport() {
        // Bound unavailable upstreams without retrying non-idempotent booking/payment requests.
        return client -> client.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(15));
    }
}
