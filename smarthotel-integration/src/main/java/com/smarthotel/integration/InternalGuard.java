package com.smarthotel.integration;
import com.smarthotel.exception.BusinessException;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Component;
@Component public class InternalGuard {
 private final byte[] secret;
 public InternalGuard(@Value("${smarthotel.internal.token}") String token){if(token.length()<32)throw new IllegalArgumentException("Internal token too short");secret=token.getBytes(StandardCharsets.UTF_8);}
 public void require(String token){if(token==null||!MessageDigest.isEqual(secret,token.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(403,"内部凭据无效");}
}
