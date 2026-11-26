package com.studysphere.matching;

public final class MatchingFailure extends RuntimeException {
    private final int status;

    MatchingFailure(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
