package com.smarthotel.hotel;
import com.smarthotel.contract.HotelCatalogContract.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:catalog;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.hikari.connection-init-sql=SELECT 1","smarthotel.internal.token=catalog-test-token-at-least-32-characters"})
class HotelCatalogHttpTest {
 @Autowired TestRestTemplate http;@Autowired JdbcTemplate db;
 LocalDate day=LocalDate.of(2030,1,1);
 @BeforeEach void init(){
  db.execute("DROP TABLE IF EXISTS room_price");db.execute("DROP TABLE IF EXISTS room_type");
  db.execute("CREATE TABLE room_type(room_type_id BIGINT PRIMARY KEY,type_code VARCHAR(30),type_name VARCHAR(80),area DECIMAL(10,2),bed_type VARCHAR(30),bed_count INT,max_guests INT,total_rooms INT,base_price DECIMAL(10,2),description VARCHAR(100),status INT,sort_no INT,create_time TIMESTAMP,update_time TIMESTAMP)");
  db.execute("CREATE TABLE room_price(price_id BIGINT PRIMARY KEY,room_type_id BIGINT,price_date DATE,sale_price DECIMAL(10,2),create_time TIMESTAMP,update_time TIMESTAMP)");
  db.update("INSERT INTO room_type(room_type_id,type_name,status,sort_no) VALUES(1,'Active',1,2),(2,'Retired',0,1),(3,'First',1,1)");
  db.update("INSERT INTO room_price(price_id,room_type_id,price_date,sale_price) VALUES(1,1,?,100),(2,1,?,110),(3,1,?,120),(4,2,?,200)",day.plusDays(2),day,day.plusDays(1),day);
 }
 ResponseEntity<List> post(String path,Object body,boolean auth){var headers=new HttpHeaders();if(auth)headers.set("X-Internal-Token","catalog-test-token-at-least-32-characters");return http.exchange("/internal/v1/hotel-catalog/"+path,HttpMethod.POST,new HttpEntity<>(body,headers),List.class);}
 @Test void historicalLookupIncludesRetiredButMissingIdsStayAbsent(){var result=post("types",new TypeQuery(List.of(2L,999L)),true);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody()).hasSize(1);assertThat((Map)result.getBody().get(0)).containsEntry("typeName","Retired");}
 @Test void pricesAreOrderedAndCheckoutIsExclusive(){var result=post("prices",new PriceQuery(List.of(1L),day,day.plusDays(2)),true);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody()).hasSize(2);assertThat((Map)result.getBody().get(0)).containsEntry("priceDate","2030-01-01");assertThat((Map)result.getBody().get(1)).containsEntry("priceDate","2030-01-02");}
 @Test void incompletePricePeriodIsReturnedHonestly(){var result=post("prices",new PriceQuery(List.of(2L),day,day.plusDays(3)),true);assertThat(result.getBody()).hasSize(1);}
 @Test void activeListFiltersRetiredAndPreservesSort(){var headers=new HttpHeaders();headers.set("X-Internal-Token","catalog-test-token-at-least-32-characters");var result=http.exchange("/internal/v1/hotel-catalog/active-types",HttpMethod.GET,new HttpEntity<>(headers),List.class);assertThat(result.getBody()).hasSize(2);assertThat((Map)result.getBody().get(0)).containsEntry("typeName","First");}
 @Test void identityBatchAndDateBoundsAreEnforced(){assertThat(http.postForEntity("/internal/v1/hotel-catalog/types",new TypeQuery(List.of(1L)),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);var h=new HttpHeaders();h.set("X-Internal-Token","catalog-test-token-at-least-32-characters");assertThat(http.postForEntity("/internal/v1/hotel-catalog/types",new HttpEntity<>(new TypeQuery(Collections.nCopies(101,1L)),h),String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(http.postForEntity("/internal/v1/hotel-catalog/prices",new HttpEntity<>(new PriceQuery(List.of(1L),day,day),h),String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
 @Test void emptyBatchNeverMeansAllRows(){assertThat(post("types",new TypeQuery(List.of()),true).getBody()).isEmpty();assertThat(post("prices",new PriceQuery(List.of(),day,day.plusDays(1)),true).getBody()).isEmpty();}
}
