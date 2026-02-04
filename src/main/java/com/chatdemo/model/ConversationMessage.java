package com.chatdemo.model;

import dev.langchain4j.data.message.ChatMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single message in a conversation with metadata for history tracking.
 */
public class ConversationMessage {
    
    private final String id;
    private final ChatMessage message;
    private boolean archived;  // true = summarized, not sent to LLM
    private final Instant createdAt;
    
    public ConversationMessage(ChatMessage message) {
        this.id = UUID.randomUUID().toString();
        this.message = message;
        this.archived = false;
        this.createdAt = Instant.now();
    }
    
    public ConversationMessage(String id, ChatMessage message, boolean archived, Instant createdAt) {
        this.id = id;
        this.message = message;
        this.archived = archived;
        this.createdAt = createdAt;
    }
    
    public String getId() {
        return id;
    }
    
    public ChatMessage getMessage() {
        return message;
    }
    
    public boolean isArchived() {
        return archived;
    }
    
    public void setArchived(boolean archived) {
        this.archived = archived;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
}
