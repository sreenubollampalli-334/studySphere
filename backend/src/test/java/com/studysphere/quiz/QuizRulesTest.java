package com.studysphere.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.studysphere.auth.AuthService.Identity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class QuizRulesTest {
    private final QuizRules.Question question = new QuizRules.Question("Choose A", "MCQ",
            List.of("A", "B"), 0, "A is the first option.", null);

    @Test
    void gradesAnswersInQuestionOrder() {
        var results = QuizRules.grade(List.of(question, question), List.of(
                new QuizRules.Selection("2", 0), new QuizRules.Selection("1", 1)));
        assertThat(results).extracting(QuizRules.Result::correct).containsExactly(false, true);
        assertThatThrownBy(() -> QuizRules.grade(List.of(question, question), List.of(
                new QuizRules.Selection("1", 1), new QuizRules.Selection("1", 0))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsMalformedModelOutputBeforePersisting() {
        var quizzes = mock(QuizService.class);
        var generation = mock(PracticeGeneration.class);
        when(generation.generate(eq(1L), eq("1"), isNull(), anyString())).thenReturn("""
                [{"prompt":"Choose A","type":"MCQ","options":["A","B"],
                  "correctIndex":"0","explanation":"A is the first option."}]
                """);
        var controller = new QuizController(quizzes, generation, new ObjectMapper());
        var student = new Identity(1, "student@example.test", List.of(), Instant.EPOCH);
        assertThatThrownBy(() -> controller.generate(student, new QuizController.Generate("Sample", "1", null, 1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verifyNoInteractions(quizzes);
    }
}
