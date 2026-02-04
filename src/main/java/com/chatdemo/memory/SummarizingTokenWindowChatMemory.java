package com.chatdemo.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom ChatMemory implementation that summarizes old messages when token limit is exceeded.
 * 
 * Unlike simple eviction, this preserves context by:
 * 1. Detecting when token limit is exceeded
 * 2. Summarizing the oldest messages using the LLM
 * 3. Replacing those messages with a summary SystemMessage
 * 4. Archiving the original messages in the repository (via HistoryAwareChatMemoryStore)
 */
public class SummarizingTokenWindowChatMemory implements ChatMemory {
    
    private static final String SUMMARIZATION_PROMPT = """
        Summarize the following conversation concisely, preserving key information, 
        decisions made, user preferences, and important context that would be needed 
        to continue the conversation naturally. Focus on facts, not the conversation flow.
        
        Conversation to summarize:
        %s
        
        Provide a concise summary:
        """;
    
    private static final String SUMMARY_PREFIX = "Summary of earlier conversation:\n";
    
    private final Object id;
    private final int maxTokens;
    private final Tokenizer tokenizer;
    private final ChatLanguageModel summarizationModel;
    private final HistoryAwareChatMemoryStore memoryStore;
    private final List<ChatMessage> messages;
    
    private SummarizingTokenWindowChatMemory(Builder builder) {
        this.id = builder.id;
        this.maxTokens = builder.maxTokens;
        this.tokenizer = builder.tokenizer;
        this.summarizationModel = builder.summarizationModel;
        this.memoryStore = builder.memoryStore;
        this.messages = new ArrayList<>(memoryStore.getMessages(id));
    }
    
    @Override
    public Object id() {
        return id;
    }
    
    @Override
    public void add(ChatMessage message) {
        messages.add(message);
        ensureCapacity();
        memoryStore.updateMessages(id, messages);
    }
    
    @Override
    public List<ChatMessage> messages() {
        return new ArrayList<>(messages);
    }
    
    @Override
    public void clear() {
        messages.clear();
        memoryStore.deleteMessages(id);
    }
    
    private void ensureCapacity() {
        int currentTokens = countTokens(messages);
        
        if (currentTokens <= maxTokens) {
            return;
        }
        
        // Find messages to summarize (oldest non-summary messages)
        List<ChatMessage> messagesToSummarize = new ArrayList<>();
        List<String> messageIdsToArchive = new ArrayList<>();
        int summaryIndex = -1;
        
        // Check if we already have a summary
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage systemMessage && 
                systemMessage.text().startsWith(SUMMARY_PREFIX)) {
                summaryIndex = i;
                break;
            }
        }
        
        // Get active message IDs before summarization
        List<String> activeIds = memoryStore.getActiveMessageIds(id.toString());
        
        // Determine how many messages to summarize to get under the limit
        // Keep at least the last 2 messages (current exchange)
        int startIndex = (summaryIndex >= 0) ? summaryIndex + 1 : 0;
        int keepCount = 2; // Keep at least the last exchange
        
        for (int i = startIndex; i < messages.size() - keepCount; i++) {
            ChatMessage msg = messages.get(i);
            
            // Skip if it's the existing summary
            if (msg instanceof SystemMessage systemMessage && 
                systemMessage.text().startsWith(SUMMARY_PREFIX)) {
                continue;
            }
            
            messagesToSummarize.add(msg);
            
            // Track ID for archiving
            if (i - startIndex < activeIds.size()) {
                messageIdsToArchive.add(activeIds.get(i - startIndex));
            }
            
            // Check if removing these messages would be enough
            List<ChatMessage> remaining = new ArrayList<>();
            if (summaryIndex >= 0) {
                remaining.add(messages.get(summaryIndex)); // Keep old summary for now
            }
            for (int j = i + 1; j < messages.size(); j++) {
                remaining.add(messages.get(j));
            }
            
            // Account for new summary (estimate)
            int estimatedNewTokens = countTokens(remaining) + 200; // Buffer for new summary
            if (estimatedNewTokens <= maxTokens) {
                break;
            }
        }
        
        if (messagesToSummarize.isEmpty()) {
            return;
        }
        
        // Generate summary
        String existingSummary = extractExistingSummary();
        String newSummary = generateSummary(existingSummary, messagesToSummarize);
        
        // Archive the messages in the repository
        if (!messageIdsToArchive.isEmpty()) {
            memoryStore.archiveMessages(id.toString(), messageIdsToArchive, newSummary);
        }
        
        // Rebuild messages list with new summary
        List<ChatMessage> newMessages = new ArrayList<>();
        newMessages.add(SystemMessage.from(SUMMARY_PREFIX + newSummary));
        
        // Add remaining messages (those not summarized)
        int summarizedCount = messagesToSummarize.size();
        for (int i = startIndex + summarizedCount; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            // Skip old summary
            if (msg instanceof SystemMessage systemMessage && 
                systemMessage.text().startsWith(SUMMARY_PREFIX)) {
                continue;
            }
            newMessages.add(msg);
        }
        
        messages.clear();
        messages.addAll(newMessages);
    }
    
    private String extractExistingSummary() {
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage systemMessage && 
                systemMessage.text().startsWith(SUMMARY_PREFIX)) {
                return systemMessage.text().substring(SUMMARY_PREFIX.length());
            }
        }
        return null;
    }
    
    private String generateSummary(String existingSummary, List<ChatMessage> messagesToSummarize) {
        StringBuilder conversationText = new StringBuilder();
        
        if (existingSummary != null) {
            conversationText.append("Previous summary:\n").append(existingSummary).append("\n\n");
            conversationText.append("New messages to incorporate:\n");
        }
        
        for (ChatMessage msg : messagesToSummarize) {
            if (msg instanceof UserMessage userMessage) {
                conversationText.append("User: ").append(userMessage.singleText()).append("\n");
            } else if (msg instanceof AiMessage aiMessage) {
                conversationText.append("Assistant: ").append(aiMessage.text()).append("\n");
            } else if (msg instanceof SystemMessage systemMessage) {
                conversationText.append("System: ").append(systemMessage.text()).append("\n");
            }
        }
        
        String prompt = String.format(SUMMARIZATION_PROMPT, conversationText);
        
        try {
            return summarizationModel.generate(prompt);
        } catch (Exception e) {
            // Fallback: create a simple summary
            return existingSummary != null 
                ? existingSummary + " [Additional context available]"
                : "[Conversation history available]";
        }
    }
    
    private int countTokens(List<ChatMessage> msgs) {
        int total = 0;
        for (ChatMessage msg : msgs) {
            total += tokenizer.estimateTokenCountInMessage(msg);
        }
        return total;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Object id;
        private int maxTokens = 4000;
        private Tokenizer tokenizer;
        private ChatLanguageModel summarizationModel;
        private HistoryAwareChatMemoryStore memoryStore;
        
        public Builder id(Object id) {
            this.id = id;
            return this;
        }
        
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }
        
        public Builder tokenizer(Tokenizer tokenizer) {
            this.tokenizer = tokenizer;
            return this;
        }
        
        public Builder chatLanguageModel(ChatLanguageModel model) {
            this.summarizationModel = model;
            return this;
        }
        
        public Builder chatMemoryStore(HistoryAwareChatMemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }
        
        public SummarizingTokenWindowChatMemory build() {
            if (id == null) {
                throw new IllegalArgumentException("id must be set");
            }
            if (tokenizer == null) {
                throw new IllegalArgumentException("tokenizer must be set");
            }
            if (summarizationModel == null) {
                throw new IllegalArgumentException("chatLanguageModel must be set");
            }
            if (memoryStore == null) {
                throw new IllegalArgumentException("chatMemoryStore must be set");
            }
            return new SummarizingTokenWindowChatMemory(this);
        }
    }
}
