package com.studysphere.quiz;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.quiz.QuizRules.Question;
import com.studysphere.quiz.QuizRules.Selection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
class QuizController {
    private static final TypeReference<List<Question>> QUESTIONS = new TypeReference<>() {};
    private final QuizService quizzes;
    private final PracticeGeneration generation;
    private final ObjectMapper json;

    QuizController(QuizService quizzes, PracticeGeneration generation, ObjectMapper json) {
        this.quizzes = quizzes;
        this.generation = generation;
        this.json = json;
    }

    @GetMapping("/quizzes")
    QuizService.Page<QuizService.Summary> list(@AuthenticationPrincipal Identity user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return quizzes.list(user.id(), page, size);
    }

    @PostMapping("/quizzes")
    @ResponseStatus(HttpStatus.CREATED)
    QuizService.Detail create(@AuthenticationPrincipal Identity user, @RequestBody Create request) {
        return quizzes.create(user.id(), request.title(), request.questions());
    }

    @PostMapping("/quizzes/generate")
    @ResponseStatus(HttpStatus.CREATED)
    QuizService.Detail generate(@AuthenticationPrincipal Identity user, @RequestBody Generate request) {
        var title = QuizRules.text(request.title(), 150);
        int count = request.count() == null ? 5 : request.count();
        if (count < 1 || count > 10) throw QuizRules.invalid("Generate 1–10 questions.");
        var output = generation.generate(user.id(), request.topicId(), request.documentId(), """
                Return a JSON array of exactly %d objects with prompt, type (MCQ or TRUE_FALSE), options (strings),
                correctIndex (zero-based integer), explanation. True/false options must be ["True","False"].
                Each explanation must explain the correct answer. No markdown, fences or other output.
                """.formatted(count));
        List<Question> questions;
        try {
            var root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            if (!root.isArray() || root.size() != count) throw new IllegalArgumentException();
            for (var item : root) {
                if (!item.isObject() || item.size() != 5 || !item.path("prompt").isString()
                        || !item.path("type").isString() || !item.path("explanation").isString()
                        || !item.path("correctIndex").isIntegralNumber() || !item.path("options").isArray()) {
                    throw new IllegalArgumentException();
                }
                for (var option : item.path("options")) if (!option.isString()) throw new IllegalArgumentException();
            }
            var parsed = json.readValue(output, QUESTIONS);
            if (parsed.size() != count) throw new IllegalArgumentException();
            questions = QuizRules.validate(parsed).stream().map(q -> new Question(q.prompt(), q.type(),
                    q.options(), q.correctIndex(), q.explanation(), request.topicId())).toList();
        } catch (RuntimeException malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI did not return a valid quiz.");
        }
        return quizzes.create(user.id(), title, questions);
    }

    @GetMapping("/quizzes/{id}")
    QuizService.Detail detail(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        return quizzes.detail(user.id(), QuizService.id(id));
    }

    @GetMapping("/quizzes/{id}/attempts")
    QuizService.Page<QuizService.Attempt> attempts(@AuthenticationPrincipal Identity user, @PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return quizzes.attempts(user.id(), QuizService.id(id), page, size);
    }

    @PostMapping("/quizzes/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    QuizService.Attempt start(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        return quizzes.start(user.id(), QuizService.id(id));
    }

    @GetMapping("/attempts/{id}")
    QuizService.Attempt attempt(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        return quizzes.attempt(user.id(), QuizService.id(id));
    }

    @PostMapping("/attempts/{id}/submit")
    QuizService.Attempt submit(@AuthenticationPrincipal Identity user, @PathVariable String id, @RequestBody Submit request) {
        return quizzes.submit(user.id(), QuizService.id(id), request.answers());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail problem(ResponseStatusException failure) {
        return failure.getBody();
    }

    record Create(String title, List<Question> questions) {}
    record Generate(String title, String topicId, String documentId, Integer count) {}
    record Submit(List<Selection> answers) {}
}
