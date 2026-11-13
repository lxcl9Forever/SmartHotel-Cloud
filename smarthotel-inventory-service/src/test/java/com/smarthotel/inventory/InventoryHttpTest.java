package com.smarthotel.inventory;
import com.smarthotel.inventory.service.*;
import com.smarthotel.contract.InventoryTccContract.Reply;
import com.smarthotel.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=InventoryController.class,properties="smarthotel.internal.token=synthetic-inventory-token-32-characters")
@Import({InventorySecurity.class,GlobalExceptionHandler.class})
class InventoryHttpTest {
 @Autowired MockMvc http;@MockitoBean InventoryOwner owner;@MockitoBean InventoryProjection projection;
 String body="{\"attemptId\":\"test\",\"orderId\":1,\"kind\":\"RESERVE\",\"roomTypeId\":1,\"from\":\"2030-01-01\",\"to\":\"2030-01-02\",\"count\":1}";
 @Test void missingInternalIdentityIsRejectedBeforeOwner()throws Exception {http.perform(post("/internal/v1/inventory/prepare").contentType("application/json").content(body)).andExpect(status().isUnauthorized());verifyNoInteractions(owner);}
 @Test void authenticatedTransportUsesUnwrappedReply()throws Exception {when(owner.prepare(any())).thenReturn(new Reply("PREPARED",false));http.perform(post("/internal/v1/inventory/prepare").header("X-Internal-Token","synthetic-inventory-token-32-characters").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("PREPARED"));}
 @Test void publicAndGetMutationPathsAreDenied()throws Exception {http.perform(get("/internal/v1/inventory/prepare")).andExpect(status().isForbidden());http.perform(get("/api/v1/bookings")).andExpect(status().isForbidden());}
}
