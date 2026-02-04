package com.chatdemo.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a conversation with full message history and summarization state.
 */
public class Conversation {
    
    private final String id;
    private String summary;
    private Instant summaryUpdatedAt;
    private final List<ConversationMessage> messages;
    
    public Conversation(String id) {
        this.id = id;
        this.summary = null;
        this.summaryUpdatedAt = null;
        this.messages = new ArrayList<>();
    }
    
    public String getId() {
        return id;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
        this.summaryUpdatedAt = Instant.now();
    }
    
    public Instant getSummaryUpdatedAt() {
        return summaryUpdatedAt;
    }
    
    public List<ConversationMessage> getMessages() {
        return messages;
    }
    
    public void addMessage(ConversationMessage message) {
        messages.add(message);
    }
    
    public void clear() {
        messages.clear();
        summary = null;
        summaryUpdatedAt = null;
    }
}
