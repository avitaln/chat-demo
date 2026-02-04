package com.chatdemo.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom ChatMemoryStore that persists chat messages to a JSON file.
 */
public class JsonFileChatMemoryStore implements ChatMemoryStore {
    
    private final File file;
    private final ObjectMapper objectMapper;
    
    public JsonFileChatMemoryStore(String filePath) {
        this.file = new File(filePath);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        
        try {
            List<Map<String, String>> entries = objectMapper.readValue(
                file, 
                new TypeReference<List<Map<String, String>>>() {}
            );
            
            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, String> entry : entries) {
                String type = entry.get("type");
                String content = entry.get("content");
                
                ChatMessage message = switch (type) {
                    case "user" -> UserMessage.from(content);
                    case "ai" -> AiMessage.from(content);
                    case "system" -> SystemMessage.from(content);
                    default -> null;
                };
                
                if (message != null) {
                    messages.add(message);
                }
            }
            return messages;
        } catch (IOException e) {
            System.err.println("Warning: Could not read chat history: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        List<Map<String, String>> entries = new ArrayList<>();
        
        for (ChatMessage message : messages) {
            String type;
            String content;
            
            if (message instanceof UserMessage userMessage) {
                type = "user";
                content = userMessage.singleText();
            } else if (message instanceof AiMessage aiMessage) {
                type = "ai";
                content = aiMessage.text();
            } else if (message instanceof SystemMessage systemMessage) {
                type = "system";
                content = systemMessage.text();
            } else {
                continue;
            }
            
            entries.add(Map.of("type", type, "content", content));
        }
        
        try {
            objectMapper.writeValue(file, entries);
        } catch (IOException e) {
            System.err.println("Warning: Could not save chat history: " + e.getMessage());
        }
    }
    
    @Override
    public void deleteMessages(Object memoryId) {
        if (file.exists()) {
            file.delete();
        }
    }
    
    /**
     * Clear all messages from the store.
     */
    public void clear() {
        deleteMessages(null);
    }
}
