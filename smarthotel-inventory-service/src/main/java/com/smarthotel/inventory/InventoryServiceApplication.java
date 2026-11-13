package com.smarthotel.inventory;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.smarthotel.exception.GlobalExceptionHandler;
@org.springframework.cloud.openfeign.EnableFeignClients
@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class) @EnableScheduling @Import(GlobalExceptionHandler.class)
public class InventoryServiceApplication {public static void main(String[] args){SpringApplication.run(InventoryServiceApplication.class,args);}}
