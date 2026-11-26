package com.studysphere.resource;

final class ResourceFailure extends RuntimeException {
    private final int status;

    ResourceFailure(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
