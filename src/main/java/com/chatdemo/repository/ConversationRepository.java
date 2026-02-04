package com.chatdemo.repository;

import com.chatdemo.model.ConversationMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Repository interface for conversation persistence.
 * Separates concerns between full history (for UI) and active messages (for LLM).
 */
public interface ConversationRepository {
    
    /**
     * Get full conversation history including archived messages.
     * Used for displaying to the user.
     */
    List<ConversationMessage> getFullHistory(String conversationId);
    
    /**
     * Get only active (non-archived) messages for the LLM.
     */
    List<ChatMessage> getActiveMessages(String conversationId);
    
    /**
     * Get the current summary of archived messages.
     * @return summary text, or null if no summarization has occurred
     */
    String getSummary(String conversationId);
    
    /**
     * Add a new message to the conversation.
     */
    void addMessage(String conversationId, ChatMessage message);
    
    /**
     * Archive messages that have been summarized.
     * Marks the specified messages as archived and updates the summary.
     */
    void archiveMessages(String conversationId, List<String> messageIds, String newSummary);
    
    /**
     * Clear all messages and summary for a conversation.
     */
    void clear(String conversationId);
}
