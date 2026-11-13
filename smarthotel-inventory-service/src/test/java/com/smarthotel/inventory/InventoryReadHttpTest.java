package com.smarthotel.inventory;
import com.smarthotel.inventory.service.*;
import com.smarthotel.contract.InventoryReadContract.Query;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:stockread;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.hikari.connection-init-sql=SELECT 1","smarthotel.internal.token=stock-read-test-token-at-least-32-characters"})
class InventoryReadHttpTest {
 @MockitoBean InventoryOwner owner;@MockitoBean InventoryProjection projection;
 @Autowired JdbcTemplate db;@Autowired TestRestTemplate http;
 LocalDate day=LocalDate.of(2030,1,1);
 @BeforeEach void init(){db.execute("DROP TABLE IF EXISTS room_inventory");db.execute("CREATE TABLE room_inventory(inventory_id BIGINT PRIMARY KEY,room_type_id BIGINT,inventory_date DATE,total_qty INT,locked_qty INT,sold_qty INT,version INT)");db.update("INSERT INTO room_inventory VALUES(1,1,?,10,2,3,1),(2,1,?,10,0,0,2),(3,2,?,5,1,0,0)",day.plusDays(1),day.plusDays(2),day);}
 ResponseEntity<String> call(Object q,boolean auth){var h=new HttpHeaders();if(auth)h.set("X-Internal-Token","stock-read-test-token-at-least-32-characters");return http.postForEntity("/internal/v1/inventory/query",new HttpEntity<>(q,h),String.class);}
 @Test void authAndBoundsRejectBeforeReading(){assertThat(call(new Query(List.of(1L),day,day.plusDays(1)),false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(call(new Query(Collections.nCopies(101,1L),day,day.plusDays(1)),true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(call(new Query(List.of(1L),day,day),true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
 @Test void rangeIsExclusiveSortedAndMissingDaysNotInvented(){var h=new HttpHeaders();h.set("X-Internal-Token","stock-read-test-token-at-least-32-characters");var r=http.postForEntity("/internal/v1/inventory/query",new HttpEntity<>(new Query(List.of(2L,1L),day,day.plusDays(2)),h),List.class);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(r.getBody()).hasSize(2);assertThat((Map)r.getBody().get(0)).containsEntry("roomTypeId",1).containsEntry("inventoryDate","2030-01-02").containsEntry("lockedQty",2);assertThat((Map)r.getBody().get(1)).containsEntry("roomTypeId",2);}
 @Test void emptyIdsNeverReturnAllStock(){assertThat(call(new Query(List.of(),day,day.plusDays(1)),true).getBody()).isEqualTo("[]");}
 @Test void dailyTotalsIncludeAllTypesForStatisticsAndRequireIdentity(){
 var h=new HttpHeaders();h.set("X-Internal-Token","stock-read-test-token-at-least-32-characters");
 assertThat(http.postForEntity("/internal/v1/inventory/day",new HttpEntity<>(day,h),List.class).getBody()).hasSize(1);
 assertThat(http.postForEntity("/internal/v1/inventory/day",day,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
}
