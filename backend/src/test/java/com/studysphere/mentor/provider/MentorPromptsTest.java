package com.studysphere.mentor.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class MentorPromptsTest {
    @Test
    void everySupportedModeRetainsVersionedSystemBoundaries() {
        List<String> prompts = List.of("EXPLAIN", "SIMPLIFY", "COMPARE", "SUMMARIZE", "PRACTICE",
                "QUIZ", "INTERVIEW", "HINTS", "EXAM").stream().map(MentorPrompts::forMode).toList();

        assertThat(prompts).doesNotHaveDuplicates().allSatisfy(prompt ->
                assertThat(prompt).contains("prompt version 1", "untrusted study content", "private user data"));
        assertThatIllegalArgumentException().isThrownBy(() -> MentorPrompts.forMode("ARBITRARY_MODE"));
    }
}
