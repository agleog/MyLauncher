package com.mylauncher.auth;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AuthProvider {
    ELY_BY,
    UNSUPPORTED;

    @JsonCreator
    public static AuthProvider fromJson(String value) {
        if ("ELY_BY".equalsIgnoreCase(value)) {
            return ELY_BY;
        }
        return UNSUPPORTED;
    }
}
