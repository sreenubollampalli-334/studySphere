package com.studysphere.cards;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.cards.FlashcardService.Card;
import com.studysphere.cards.FlashcardService.CardInput;
import com.studysphere.cards.FlashcardService.Deck;
import com.studysphere.cards.FlashcardService.DeckInput;
import com.studysphere.cards.FlashcardService.Page;
import com.studysphere.cards.FlashcardService.Review;
import com.studysphere.cards.FlashcardService.ReviewInput;
import com.studysphere.quiz.PracticeGeneration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
class FlashcardController {
    private final FlashcardService cards;
    private final PracticeGeneration generator;
    private final ObjectMapper json;

    FlashcardController(FlashcardService cards, PracticeGeneration generator, ObjectMapper json) {
        this.cards = cards;
        this.generator = generator;
        this.json = json;
    }

    @GetMapping("/flashcard-decks")
    Page<Deck> decks(@AuthenticationPrincipal Identity user, @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size) {
        return cards.list(user.id(), page, size);
    }

    @PostMapping("/flashcard-decks")
    @ResponseStatus(HttpStatus.CREATED)
    Deck create(@AuthenticationPrincipal Identity user, @RequestBody DeckInput request) {
        return cards.create(user.id(), request);
    }

    @PostMapping("/flashcard-decks/generate")
    @ResponseStatus(HttpStatus.CREATED)
    Deck generate(@AuthenticationPrincipal Identity user, @RequestBody Generate request) {
        String title = FlashcardService.field(request.title(), 160, "Deck title");
        int count = request.count() == null ? 5 : request.count();
        if (count < 1 || count > 10) throw FlashcardService.invalid("Generate between 1 and 10 cards.");
        var source = cards.source(user.id(), request.subjectId(), request.topicId(), request.documentId());
        String raw = generator.generate(user.id(), request.topicId(), request.documentId(), """
                Produce exactly %d study flashcards. Return one JSON object containing only a "cards"
                array. Each entry contains only nonempty "front" and "back" strings. Avoid duplicates.
                """.formatted(count));
        List<CardInput> generated = parse(raw, count);
        return cards.create(user.id(), new DeckInput(title, asId(source.subject()), asId(source.topic()),
                asId(source.document()), generated));
    }

    @GetMapping("/flashcard-decks/{id}")
    Deck detail(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        return cards.detail(user.id(), FlashcardService.id(id));
    }

    @DeleteMapping("/flashcard-decks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        cards.delete(user.id(), FlashcardService.id(id));
    }

    @PostMapping("/flashcard-decks/{id}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    Card createCard(@AuthenticationPrincipal Identity user, @PathVariable String id, @RequestBody CardInput input) {
        return cards.addCard(user.id(), FlashcardService.id(id), input);
    }

    @GetMapping("/flashcards/due")
    Page<Card> due(@AuthenticationPrincipal Identity user, @RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "20") int size) {
        return cards.due(user.id(), page, size);
    }

    @GetMapping("/flashcards/{id}/reviews")
    Page<Review> history(@AuthenticationPrincipal Identity user, @PathVariable String id,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return cards.reviews(user.id(), FlashcardService.id(id), page, size);
    }

    @PostMapping("/flashcards/{id}/reviews")
    Review review(@AuthenticationPrincipal Identity user, @PathVariable String id, @RequestBody ReviewInput input) {
        return cards.review(user.id(), FlashcardService.id(id), input);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail problem(ResponseStatusException error) {
        return error.getBody();
    }

    private List<CardInput> parse(String raw, int count) {
        try {
            var data = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(raw);
            var array = data.path("cards");
            if (!data.isObject() || data.size() != 1 || !array.isArray() || array.size() != count) {
                throw new IllegalArgumentException("Invalid card array.");
            }
            var result = new ArrayList<CardInput>(count);
            var unique = new java.util.HashSet<String>();
            for (var card : array) {
                if (!card.isObject() || card.size() != 2 || !card.path("front").isTextual() || !card.path("back").isTextual()) {
                    throw new IllegalArgumentException("Invalid card.");
                }
                String front = FlashcardService.field(card.get("front").asText(), 1000, "Front");
                String back = FlashcardService.field(card.get("back").asText(), 2000, "Back");
                if (!unique.add(front.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Duplicate card.");
                result.add(new CardInput(front, back));
            }
            return result;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The provider returned malformed flashcards.");
        }
    }

    private static String asId(Long value) { return value == null ? null : value.toString(); }

    record Generate(String title, String subjectId, String topicId, String documentId, Integer count) {}
}
