package com.chatdemo.memory;

import com.chatdemo.model.ConversationMessage;
import com.chatdemo.repository.ConversationRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMemoryStore adapter that bridges LangChain4j's memory system to our ConversationRepository.
 * 
 * This store:
 * - Returns summary + active messages when getMessages() is called
 * - Detects newly added messages and persists them
 * - Does NOT handle archiving directly (that's done by SummarizingTokenWindowChatMemory)
 */
public class HistoryAwareChatMemoryStore implements ChatMemoryStore {
    
    private final ConversationRepository repository;
    
    public HistoryAwareChatMemoryStore(ConversationRepository repository) {
        this.repository = repository;
    }
    
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        
        List<ChatMessage> result = new ArrayList<>();
        
        // Add summary as SystemMessage if present
        String summary = repository.getSummary(conversationId);
        if (summary != null && !summary.isEmpty()) {
            result.add(SystemMessage.from("Summary of earlier conversation:\n" + summary));
        }
        
        // Add active (non-archived) messages
        result.addAll(repository.getActiveMessages(conversationId));
        
        return result;
    }
    
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String conversationId = memoryId.toString();
        
        // Get current active messages from repository
        List<ChatMessage> currentMessages = repository.getActiveMessages(conversationId);
        
        // Find messages in the incoming list that are NOT the summary SystemMessage
        // and are not already in the repository
        for (ChatMessage message : messages) {
            // Skip summary system messages (they start with "Summary of earlier conversation")
            if (message instanceof SystemMessage systemMessage) {
                if (systemMessage.text().startsWith("Summary of earlier conversation")) {
                    continue;
                }
            }
            
            // Check if this message already exists in the repository
            if (!containsMessage(currentMessages, message)) {
                repository.addMessage(conversationId, message);
            }
        }
    }
    
    @Override
    public void deleteMessages(Object memoryId) {
        repository.clear(memoryId.toString());
    }
    
    /**
     * Archives messages and updates the summary.
     * Called by SummarizingTokenWindowChatMemory when summarization occurs.
     */
    public void archiveMessages(String conversationId, List<String> messageIds, String summary) {
        repository.archiveMessages(conversationId, messageIds, summary);
    }
    
    /**
     * Get message IDs for active messages (used by summarizing memory to know what to archive).
     */
    public List<String> getActiveMessageIds(String conversationId) {
        return repository.getFullHistory(conversationId).stream()
            .filter(msg -> !msg.isArchived())
            .map(ConversationMessage::getId)
            .toList();
    }
    
    /**
     * Get full history for UI display.
     */
    public List<ConversationMessage> getFullHistory(String conversationId) {
        return repository.getFullHistory(conversationId);
    }
    
    private boolean containsMessage(List<ChatMessage> messages, ChatMessage target) {
        for (ChatMessage msg : messages) {
            if (messagesEqual(msg, target)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean messagesEqual(ChatMessage a, ChatMessage b) {
        if (a.getClass() != b.getClass()) {
            return false;
        }
        return a.equals(b);
    }
}
