package com.smarthotel.inventory.client;
import feign.*;import org.springframework.context.annotation.Bean;
public class ReadTransportConfiguration {@Bean public Retryer retryer(){return Retryer.NEVER_RETRY;} @Bean public Request.Options options(){return new Request.Options(1500,2000);} }
