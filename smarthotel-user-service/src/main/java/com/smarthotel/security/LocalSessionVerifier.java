package com.smarthotel.security;
import com.smarthotel.mapper.SysUserMapper;
import com.smarthotel.mapper.SysRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class LocalSessionVerifier implements SessionVerifier {
 private final JwtTokenProvider tokens;
 private final SysUserMapper users;
 private final SysRoleMapper roles;
 public SessionIdentity verify(String token) {
  io.jsonwebtoken.Claims claims;
  try { claims=tokens.parseToken(token); } catch(io.jsonwebtoken.JwtException | IllegalArgumentException invalid) { return null; }
  Long id=claims.get("userId",Long.class);
  var user=id==null?null:users.selectById(id);
  if(user==null || !"ACTIVE".equals(user.getStatus()) || !user.getUsername().equals(claims.getSubject()) || !tokens.matchesCredentialVersion(claims,user.getPassword())) return null;
  var role=roles.selectById(user.getRoleId());
  if(role==null || !Integer.valueOf(1).equals(role.getStatus()) || !java.util.Set.of("CUSTOMER","STAFF","ADMIN").contains(role.getRoleCode()) || !role.getRoleCode().equals(claims.get("role",String.class))) return null;
  return new SessionIdentity(id,user.getUsername(),role.getRoleCode());
 }
}
