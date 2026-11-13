package com.smarthotel.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smarthotel.contract.DirectoryAccount;
import com.smarthotel.contract.DirectoryCustomer;
import com.smarthotel.contract.UserDirectoryContract.*;
import com.smarthotel.entity.Customer;
import com.smarthotel.entity.SysUser;
import com.smarthotel.mapper.CustomerMapper;
import com.smarthotel.mapper.SysRoleMapper;
import com.smarthotel.mapper.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/internal/v1/directory")
public class InternalDirectoryController {
    private final SysUserMapper users;
    private final CustomerMapper customers;
    private final SysRoleMapper roles;
    private final byte[] secret;
    public InternalDirectoryController(SysUserMapper users, CustomerMapper customers, SysRoleMapper roles,
                                       @Value("${smarthotel.internal.token}") String token) {
        if (token.length()<32) throw new IllegalArgumentException("INTERNAL_TOKEN must contain at least 32 characters");
        this.users=users;this.customers=customers;this.roles=roles;
        secret=token.getBytes(StandardCharsets.UTF_8);
    }
    private void authorize(String key) {
        if (!MessageDigest.isEqual(secret,key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
    @PostMapping("/account")
    public AccountReply account(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,
                                @Valid @RequestBody AccountQuery query) {
        authorize(key);
        boolean hasName=query.username()!=null && !query.username().isBlank();
        if (hasName == (query.userId()!=null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        SysUser row=hasName ? users.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername,query.username()))
                : users.selectById(query.userId());
        if(row==null)return new AccountReply(null);
        var account=new DirectoryAccount();account.setUserId(row.getUserId());account.setUsername(row.getUsername());
        return new AccountReply(account);
    }
    @PostMapping("/customer")
    public CustomerReply customer(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,
                                  @Valid @RequestBody CustomerQuery query) {
        authorize(key);
        if(query.userId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Customer row=customers.selectOne(new LambdaQueryWrapper<Customer>().eq(Customer::getUserId,query.userId()));
        if(row==null)return new CustomerReply(null);
        var customer=new DirectoryCustomer();customer.setCustomerId(row.getCustomerId());
        customer.setUserId(row.getUserId());customer.setCustomerName(row.getCustomerName());
        return new CustomerReply(customer);
    }
    @PostMapping("/names")
    public NamesReply names(@RequestHeader(value="X-Internal-Token",defaultValue="") String key,
                            @Valid @RequestBody NamesQuery query) {
        authorize(key);
        var usernames=new LinkedHashMap<Long,String>();
        var customerNames=new LinkedHashMap<Long,String>();
        if(query.userIds()!=null && !query.userIds().isEmpty()) {
            for(var row: users.selectList(new LambdaQueryWrapper<SysUser>().select(SysUser::getUserId,SysUser::getUsername)
                    .in(SysUser::getUserId,query.userIds()))) {
                if(row.getUsername()!=null)usernames.put(row.getUserId(),row.getUsername());
            }
        }
        if(query.customerIds()!=null && !query.customerIds().isEmpty()) {
            for(var row: customers.selectList(new LambdaQueryWrapper<Customer>()
                    .select(Customer::getCustomerId,Customer::getCustomerName).in(Customer::getCustomerId,query.customerIds()))) {
                if(row.getCustomerName()!=null)customerNames.put(row.getCustomerId(),row.getCustomerName());
            }
        }
        return new NamesReply(usernames,customerNames);
    }
    @PostMapping("/roles")
    public RolesReply roles(@RequestHeader(value="X-Internal-Token",defaultValue="") String key) {
        authorize(key);
        return new RolesReply(roles.selectList(null).stream()
                .map(row -> new Role(row.getRoleId(),row.getRoleCode(),row.getRoleName(),row.getStatus())).toList());
    }
}
