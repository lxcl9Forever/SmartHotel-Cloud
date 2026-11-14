package com.smarthotel.service;

import com.smarthotel.client.UserDirectoryClient;
import com.smarthotel.contract.DirectoryAccount;
import com.smarthotel.contract.DirectoryCustomer;
import com.smarthotel.contract.UserDirectoryContract.*;
import com.smarthotel.exception.BusinessException;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

@Service
public class UserDirectory {
    private final UserDirectoryClient client;
    private final String token;
    public UserDirectory(UserDirectoryClient client,@Value("${smarthotel.internal.token}") String token) {
        if(token.length()<32)throw new IllegalArgumentException("INTERNAL_TOKEN must contain at least 32 characters");
        this.client=client;this.token=token;
    }
    private <T> T read(Supplier<T> action) {
        try {
            T value=action.get();
            if(value==null)throw unavailable();
            return value;
        } catch(FeignException error) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(503,"用户资料服务暂不可用，请稍后重试"); }
    public DirectoryAccount findByUsername(String username) {
        var account=read(() -> client.account(token,new AccountQuery(username,null))).account();
        if(account!=null && (account.getUserId()==null || account.getUserId()<=0 || !Objects.equals(username,account.getUsername())))throw unavailable();
        return account;
    }
    public DirectoryCustomer findCustomerByUserId(Long userId) {
        var customer=read(() -> client.customer(token,new CustomerQuery(userId))).customer();
        if(customer!=null && (customer.getCustomerId()==null || customer.getCustomerId()<=0 || !Objects.equals(userId,customer.getUserId())))throw unavailable();
        return customer;
    }
    public Long requireCustomerId(String username) {
        var account=findByUsername(username);
        if(account==null)throw new BusinessException(401,"当前登录用户不存在");
        var customer=findCustomerByUserId(account.getUserId());
        if(customer==null)throw new BusinessException(403,"当前账号没有客户档案");
        return customer.getCustomerId();
    }
    public NamesReply names(Collection<Long> users,Collection<Long> customers) {
        var userIds=users.stream().filter(Objects::nonNull).distinct().toList();
        var customerIds=customers.stream().filter(Objects::nonNull).distinct().toList();
        var usernames=new HashMap<Long,String>();var customerNames=new HashMap<Long,String>();
        for(int start=0;start<Math.max(userIds.size(),customerIds.size());start+=100) {
            var u=userIds.subList(Math.min(start,userIds.size()),Math.min(start+100,userIds.size()));
            var c=customerIds.subList(Math.min(start,customerIds.size()),Math.min(start+100,customerIds.size()));
            var reply=read(() -> client.names(token,new NamesQuery(u,c)));
            if(reply.usernames()==null || reply.customerNames()==null)throw unavailable();
            usernames.putAll(reply.usernames());customerNames.putAll(reply.customerNames());
        }
        return new NamesReply(usernames,customerNames);
    }
    public List<Role> roles() {
        var roles=read(() -> client.roles(token)).roles();
        if(roles==null)throw unavailable();
        return roles;
    }
}
