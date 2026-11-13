package com.smarthotel.hotel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.smarthotel.exception.GlobalExceptionHandler;
@org.springframework.cloud.openfeign.EnableFeignClients
@SpringBootApplication
@Import(GlobalExceptionHandler.class)
public class HotelServiceApplication {
 public static void main(String[] args) { SpringApplication.run(HotelServiceApplication.class,args); }
}
