package com.studysphere.planner;

final class PlannerFailure extends RuntimeException {
    private final int status;
    private final String code;

    PlannerFailure(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    int status() { return status; }
    String code() { return code; }
}
