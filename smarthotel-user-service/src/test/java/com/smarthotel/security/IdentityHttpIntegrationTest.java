package com.smarthotel.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarthotel.service.AuthAttemptLimiter;
import com.smarthotel.service.CaptchaService;
import com.smarthotel.service.OperationLogService;
import com.smarthotel.service.PasswordResetRateLimiter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP + H2 test. Only Redis-backed captcha/limits and audit storage are mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:identity;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.connection-init-sql=", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:identity-schema.sql",
    "smarthotel.internal.token=identity-integration-internal-token-32-chars",
    "jwt.secret=identity-integration-signing-secret-32-chars"
})
class IdentityHttpIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CaptchaService captcha;
    @MockitoBean AuthAttemptLimiter limiter;
    @MockitoBean PasswordResetRateLimiter resetLimiter;
    @MockitoBean OperationLogService audit;

    @Test void registerLoginChangePasswordAndDisableRevokeTokensOverHttp() {
        var registration = Map.of("username", "cloud_guest", "password", "FirstPass123!",
                "name", "Cloud Guest", "phone", "13900001234");
        assertThat(http.postForEntity("/api/v1/auth/register", registration, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.postForEntity("/api/v1/auth/register", registration, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String token = login("FirstPass123!");
        assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(introspect(token, "wrong-key").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(introspect(token, internalKey()).getBody().path("active").asBoolean()).isTrue();
        assertThat(introspect(token, internalKey()).getBody().toString()).doesNotContain("password", "credentialVersion");
        assertThat(http.exchange("/api/v1/admin/users", HttpMethod.GET,
                authenticated(token, null), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var changed = http.exchange("/api/v1/customer/profile/password", HttpMethod.PUT,
                authenticated(token, Map.of("oldPassword", "FirstPass123!", "newPassword", "SecondPass123!")), JsonNode.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(introspect(token, internalKey()).getBody().path("active").asBoolean()).isFalse();
        String replacement = login("SecondPass123!");
        assertThat(me(replacement).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE username='cloud_guest'");
        assertThat(me(replacement).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(introspect(replacement, internalKey()).getBody().path("active").asBoolean()).isFalse();
    }

    @Test void directoryRequiresInternalKeyAndExposesOnlyMinimalReferences() {
        var registration=Map.of("username","directory_guest","password","TestPass123!",
                "name","Directory Guest","phone","13900001235");
        assertThat(http.postForEntity("/api/v1/auth/register",registration,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var query=Map.of("username","directory_guest");
        assertThat(directory("account",query,"wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var reply=directory("account",query,internalKey());
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.OK);
        var account=reply.getBody().path("account");
        assertThat(account.path("username").asText()).isEqualTo("directory_guest");
        assertThat(account.size()).isEqualTo(2);
        long userId=account.path("userId").asLong();
        var customer=directory("customer",Map.of("userId",userId),internalKey()).getBody().path("customer");
        assertThat(customer.path("userId").asLong()).isEqualTo(userId);
        assertThat(customer.size()).isEqualTo(3);
        long customerId=customer.path("customerId").asLong();
        var names=directory("names",Map.of("userIds",java.util.List.of(userId),"customerIds",java.util.List.of(customerId)),internalKey());
        assertThat(names.getBody().path("usernames").path(String.valueOf(userId)).asText()).isEqualTo("directory_guest");
        assertThat(names.getBody().path("customerNames").path(String.valueOf(customerId)).asText()).isEqualTo("Directory Guest");
        assertThat(names.getBody().toString()).doesNotContain("password","idNumber","phone");
        assertThat(directory("names",Map.of("userIds",java.util.Collections.nCopies(101,userId)),internalKey()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(directory("account",Map.of("username","directory_guest","userId",userId),internalKey()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(directory("account",Map.of("username","missing_account"),internalKey()).getBody().path("account").isNull()).isTrue();
    }
    private ResponseEntity<JsonNode> directory(String path,Object body,String key) {
        var headers=new HttpHeaders();headers.set("X-Internal-Token",key);
        return http.postForEntity("/internal/v1/directory/"+path,new HttpEntity<>(body,headers),JsonNode.class);
    }

    private String login(String password) {
        var response=http.postForEntity("/api/v1/auth/login", Map.of("username","cloud_guest",
                "password",password,"captchaId","test-captcha","captchaCode","ABCDE"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("data").path("token").asText();
    }
    private ResponseEntity<JsonNode> me(String token) {
        return http.exchange("/api/v1/user/me",HttpMethod.GET,authenticated(token,null),JsonNode.class);
    }
    private HttpEntity<Object> authenticated(String token,Object body) {
        var headers=new HttpHeaders();headers.setBearerAuth(token);headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body,headers);
    }
    private ResponseEntity<JsonNode> introspect(String token,String key) {
        var headers=new HttpHeaders();headers.set("X-Internal-Token",key);
        return http.postForEntity("/internal/v1/sessions/introspect",new HttpEntity<>(Map.of("token",token),headers),JsonNode.class);
    }
    private String internalKey(){return "identity-integration-internal-token-32-chars";}
}
