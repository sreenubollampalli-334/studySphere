package com.studysphere.auth;

public interface AuthMailer {

    void sendVerification(String email, String link);

    void sendPasswordReset(String email, String link);
}
