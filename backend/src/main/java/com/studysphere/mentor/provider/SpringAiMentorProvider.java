package com.studysphere.mentor.provider;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
final class SpringAiMentorProvider implements MentorProvider {
    private final ChatClient client;
    private final ChatModel model;
    private final String provider;

    SpringAiMentorProvider(ObjectProvider<ChatModel> models, @Value("${spring.ai.model.chat:none}") String provider) {
        if (!List.of("none", "openai", "google-genai").contains(provider)) {
            throw new IllegalArgumentException("Choose none, openai or google-genai for STUDYSPHERE_AI_PROVIDER.");
        }
        this.provider = provider;
        this.model = provider.equals("none") ? null : models.getObject();
        this.client = model == null ? null : ChatClient.create(model);
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public String id() {
        return provider;
    }

    @Override
    public Flux<String> stream(String mode, List<Turn> turns) {
        if (client == null) return Flux.error(new IllegalStateException("No tutor provider is configured."));

        return Flux.defer(() -> {
            List<Message> messages = new ArrayList<>(turns.size() + 1);
            messages.add(new SystemMessage(MentorPrompts.forMode(mode)));
            for (Turn turn : turns) {
                messages.add(switch (turn.role()) {
                    case "USER", "user" -> new UserMessage(turn.text());
                    case "ASSISTANT", "assistant" -> new AssistantMessage(turn.text());
                    default -> throw new IllegalArgumentException("Unsupported tutor history role.");
                });
            }
            var prompt = "PLAN_PROPOSAL".equals(mode)
                    ? new Prompt(messages, model.getOptions().mutate().maxTokens(3600).build())
                    : new Prompt(messages);
            return client.prompt(prompt).stream().content()
                    .filter(content -> !content.isEmpty());
        });
    }
}
