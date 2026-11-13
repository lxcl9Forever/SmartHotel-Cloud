package com.smarthotel.service;
import com.smarthotel.client.UserDirectoryClient;
import com.smarthotel.contract.DirectoryCustomer;
import com.smarthotel.contract.UserDirectoryContract.*;
import com.smarthotel.exception.BusinessException;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserDirectoryTest {
    private final UserDirectoryClient client=mock(UserDirectoryClient.class);
    private final UserDirectory directory=new UserDirectory(client,"directory-test-internal-key-32-characters");
    @Test void remoteFailureIs503AndNeverFallsBackToAUserTable() {
        when(client.customer(anyString(),any())).thenThrow(mock(FeignException.class));
        assertThatThrownBy(() -> directory.findCustomerByUserId(1L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(503));
    }
    @Test void missingCustomerIsDifferentFromMalformedOrMissingRemoteReply() {
        when(client.customer(anyString(),any())).thenReturn(new CustomerReply(null),null);
        assertThat(directory.findCustomerByUserId(1L)).isNull();
        assertThatThrownBy(() -> directory.findCustomerByUserId(1L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(503));
        var wrong=new DirectoryCustomer();wrong.setCustomerId(10L);wrong.setUserId(2L);
        when(client.customer(anyString(),any())).thenReturn(new CustomerReply(wrong));
        assertThatThrownBy(() -> directory.findCustomerByUserId(1L)).isInstanceOf(BusinessException.class);
    }
    @Test void displayLookupDeduplicatesAndBatchesInsteadOfCallingForEveryRow() {
        when(client.names(anyString(),any())).thenReturn(new NamesReply(Map.of(),Map.of()));
        directory.names(LongStream.rangeClosed(1,205).boxed().toList(),List.of(1L,1L));
        verify(client,times(3)).names(anyString(),argThat(q -> q.userIds().size()<=100 && q.customerIds().size()<=100));
        directory.names(List.of(),List.of());
        verifyNoMoreInteractions(client);
    }
    @Test void businessSourceMustNotQueryUserOwnedTables() throws Exception {
        var forbidden=Pattern.compile("(?i)\\b(from|join|update|into)\\s+(sys_user|customer|employee|sys_role|member_level|customer_saved_guest)\\b|BaseMapper\\s*<\\s*(SysUser|Customer|Employee|SysRole|MemberLevel|CustomerSavedGuest)\\s*>");
        try(var files=Files.walk(Path.of("src/main/java"))) {
            for(var path:files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(forbidden.matcher(Files.readString(path)).find()).as(path.toString()).isFalse();
            }
        }
    }
}
