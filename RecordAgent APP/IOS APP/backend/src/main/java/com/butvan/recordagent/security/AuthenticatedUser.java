package com.butvan.recordagent.security;

import java.util.UUID;

/** 当前请求经服务端凭据解析出的身份。 */
public record AuthenticatedUser(UUID userId, UUID sessionId) {}
