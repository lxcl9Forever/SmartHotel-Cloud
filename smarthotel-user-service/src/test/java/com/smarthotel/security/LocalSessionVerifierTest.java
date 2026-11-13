package com.smarthotel.security;
import com.smarthotel.config.JwtProperties;
import com.smarthotel.entity.SysUser;
import com.smarthotel.entity.SysRole;
import com.smarthotel.mapper.SysUserMapper;
import com.smarthotel.mapper.SysRoleMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalSessionVerifierTest {
 @Test void roleChangesAndCredentialChangesInvalidateExistingJwt() {
  var props=new JwtProperties();props.setSecret("a-random-test-signing-secret-at-least-32-chars");props.setExpiration(60000L);
  var tokens=new JwtTokenProvider(props);
  var users=mock(SysUserMapper.class);var roles=mock(SysRoleMapper.class);
  var user=new SysUser();user.setUserId(1L);user.setUsername("guest");user.setPassword("old-hash");user.setStatus("ACTIVE");user.setRoleId(1L);
  var role=new SysRole();role.setRoleCode("CUSTOMER");role.setStatus(1);
  when(users.selectById(1L)).thenReturn(user);when(roles.selectById(1L)).thenReturn(role);
  var verifier=new LocalSessionVerifier(tokens,users,roles);
  String jwt=tokens.generateToken(1L,"guest","CUSTOMER","old-hash");
  assertThat(verifier.verify(jwt).userId()).isEqualTo(1L);
  role.setRoleCode("ADMIN");assertThat(verifier.verify(jwt)).isNull();
  role.setRoleCode("CUSTOMER");user.setPassword("new-hash");assertThat(verifier.verify(jwt)).isNull();
  assertThat(verifier.verify("garbage")).isNull();
 }
}
