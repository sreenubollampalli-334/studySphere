package com.studysphere.mentor.provider;

import java.util.List;
import reactor.core.publisher.Flux;

public interface MentorProvider {
    boolean available();

    String id();

    Flux<String> stream(String mode, List<Turn> turns);

    record Turn(String role, String text) {}
}
