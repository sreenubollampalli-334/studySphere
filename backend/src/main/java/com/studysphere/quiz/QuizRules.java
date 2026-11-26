package com.studysphere.quiz;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class QuizRules {
    record Question(String prompt, String type, List<String> options, Integer correctIndex, String explanation, String topicId) {}
    record Selection(String questionId, Integer selectedIndex) {}
    record Result(String questionId, int selectedIndex, int correctIndex, boolean correct, String explanation) {}

    static String text(String input, int max) {
        if (input == null || input.isBlank() || input.length() > max) throw invalid("Invalid quiz text.");
        return input.strip();
    }

    static List<Question> validate(List<Question> questions) {
        if (questions == null || questions.isEmpty() || questions.size() > 20) throw invalid("Provide 1–20 questions.");
        return questions.stream().map(question -> {
            if (question == null || question.options() == null || question.correctIndex() == null
                    || question.type() == null) throw invalid("Invalid question.");
            var options = question.options().stream().map(option -> text(option, 350)).toList();
            switch (question.type()) {
                case "MCQ" -> {
                    if (options.size() < 2 || options.size() > 6
                            || options.stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).distinct().count() != options.size())
                        throw invalid("Multiple choice questions need 2–6 distinct options.");
                }
                case "TRUE_FALSE" -> {
                    if (options.size() != 2 || !options.get(0).equalsIgnoreCase("true") || !options.get(1).equalsIgnoreCase("false"))
                        throw invalid("True/false options must be True followed by False.");
                }
                default -> throw invalid("Unsupported question type.");
            }
            if (question.correctIndex() < 0 || question.correctIndex() >= options.size()) throw invalid("Invalid answer key.");
            return new Question(text(question.prompt(), 700), question.type(), options, question.correctIndex(),
                    text(question.explanation(), 1200), question.topicId());
        }).toList();
    }

    static List<Result> grade(List<Question> questions, List<Selection> answers) {
        if (answers == null || answers.size() != questions.size()) throw invalid("Answer every question exactly once.");
        var byId = new HashMap<String, Integer>();
        for (var answer : answers) {
            if (answer == null || answer.questionId() == null || answer.selectedIndex() == null
                    || byId.putIfAbsent(answer.questionId(), answer.selectedIndex()) != null) {
                throw invalid("Invalid or duplicate answer.");
            }
        }
        return java.util.stream.IntStream.range(0, questions.size()).mapToObj(index -> {
            String id = Integer.toString(index + 1);
            var question = questions.get(index);
            Integer selected = byId.get(id);
            if (selected == null || selected < 0 || selected >= question.options().size()) throw invalid("Invalid answer option.");
            return new Result(id, selected, question.correctIndex(), Objects.equals(selected, question.correctIndex()), question.explanation());
        }).toList();
    }

    static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
