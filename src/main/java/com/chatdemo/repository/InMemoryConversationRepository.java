package com.chatdemo.repository;

import com.chatdemo.model.Conversation;
import com.chatdemo.model.ConversationMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of ConversationRepository.
 * Thread-safe using ConcurrentHashMap.
 */
public class InMemoryConversationRepository implements ConversationRepository {
    
    private final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();
    
    private Conversation getOrCreateConversation(String conversationId) {
        return conversations.computeIfAbsent(conversationId, Conversation::new);
    }
    
    @Override
    public List<ConversationMessage> getFullHistory(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(conversation.getMessages());
    }
    
    @Override
    public List<ChatMessage> getActiveMessages(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return new ArrayList<>();
        }
        
        return conversation.getMessages().stream()
            .filter(msg -> !msg.isArchived())
            .map(ConversationMessage::getMessage)
            .toList();
    }
    
    @Override
    public String getSummary(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return null;
        }
        return conversation.getSummary();
    }
    
    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        Conversation conversation = getOrCreateConversation(conversationId);
        conversation.addMessage(new ConversationMessage(message));
    }
    
    @Override
    public void archiveMessages(String conversationId, List<String> messageIds, String newSummary) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            return;
        }
        
        // Mark specified messages as archived
        for (ConversationMessage msg : conversation.getMessages()) {
            if (messageIds.contains(msg.getId())) {
                msg.setArchived(true);
            }
        }
        
        // Update the summary
        conversation.setSummary(newSummary);
    }
    
    @Override
    public void clear(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation != null) {
            conversation.clear();
        }
    }
}
