package com.smarthotel.security;
public record SessionIdentity(Long userId, String username, String role) {}
