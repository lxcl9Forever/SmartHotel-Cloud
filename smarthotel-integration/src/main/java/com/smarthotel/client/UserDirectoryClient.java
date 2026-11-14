package com.smarthotel.client;
import com.smarthotel.contract.UserDirectoryContract.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="smarthotel-user-service",contextId="userDirectory",url="${smarthotel.services.user-url:}")
public interface UserDirectoryClient {
    @PostMapping("/internal/v1/directory/account")
    AccountReply account(@RequestHeader("X-Internal-Token") String token,@RequestBody AccountQuery query);
    @PostMapping("/internal/v1/directory/customer")
    CustomerReply customer(@RequestHeader("X-Internal-Token") String token,@RequestBody CustomerQuery query);
    @PostMapping("/internal/v1/directory/names")
    NamesReply names(@RequestHeader("X-Internal-Token") String token,@RequestBody NamesQuery query);
    @PostMapping("/internal/v1/directory/roles")
    RolesReply roles(@RequestHeader("X-Internal-Token") String token);
}
