package com.abe.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 对话模型装配。
 * 独立 OpenAiApi 实例：DeepSeek 支持 /v1/chat/completions 但没有 /v1/embeddings，
 * 因此与 embedding 的 base-url / api-key 必须分开，互不影响。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagChatConfiguration {

    @Bean
    public ChatModel abeChatModel(RagProperties properties) {
        RagProperties.Chat chat = properties.getChat();
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(chat.getModel())
                .temperature(chat.getTemperature())
                .maxTokens(chat.getMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public ChatClient abeChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
