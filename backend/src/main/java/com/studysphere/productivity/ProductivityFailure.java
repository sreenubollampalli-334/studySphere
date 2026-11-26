package com.studysphere.productivity;

final class ProductivityFailure extends RuntimeException {

    private final int status;
    private final String code;

    ProductivityFailure(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
