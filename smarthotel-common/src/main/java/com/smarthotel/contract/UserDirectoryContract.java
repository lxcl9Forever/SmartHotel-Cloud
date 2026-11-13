package com.smarthotel.contract;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
public final class UserDirectoryContract {
    private UserDirectoryContract() {}
    public record AccountQuery(@Size(max=64) String username, @Positive Long userId) {}
    public record AccountReply(DirectoryAccount account) {}
    public record CustomerQuery(@Positive Long userId) {}
    public record CustomerReply(DirectoryCustomer customer) {}
    public record NamesQuery(@Size(max=100) List<@Positive Long> userIds,
                             @Size(max=100) List<@Positive Long> customerIds) {}
    public record NamesReply(Map<Long, String> usernames, Map<Long, String> customerNames) {}
    public record Role(Long roleId, String roleCode, String roleName, Integer status) {}
    public record RolesReply(List<Role> roles) {}
}
