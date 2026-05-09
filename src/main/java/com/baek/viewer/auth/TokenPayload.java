package com.baek.viewer.auth;

public record TokenPayload(long issuedAtMs, AuthRole role, Long editorAssigneeId) {}
