package com.studysphere.profile;

public final class ProfileFailure extends RuntimeException {

    private final int status;
    private final String code;

    ProfileFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }

    public String code() { return code; }
}
