package com.chatdemo;

import com.chatdemo.config.ModelsConfig;
import com.chatdemo.memory.HistoryAwareChatMemoryStore;
import com.chatdemo.memory.SummarizingTokenWindowChatMemory;
import com.chatdemo.repository.ConversationRepository;
import com.chatdemo.repository.InMemoryConversationRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

import java.util.List;
import java.util.Scanner;

/**
 * Main CLI application for multi-provider AI chatbot using LangChain4j.
 * Supports switching between Gemini, ChatGPT, Claude, and Grok.
 * Uses summarizing chat memory with full history preservation.
 */
public class ChatApplication {
    
    private static final String MEMORY_ID = "default";
    private static final int MAX_TOKENS = 4000;
    
    private final List<ProviderConfig> configs;
    private final ConversationRepository conversationRepository;
    private final HistoryAwareChatMemoryStore memoryStore;
    private final Tokenizer tokenizer;
    private int currentIndex;
    private Assistant currentAssistant;
    
    /**
     * AI Assistant interface - LangChain4j will implement this.
     */
    interface Assistant {
        String chat(@MemoryId String memoryId, @UserMessage String message);
    }
    
    public ChatApplication() {
        this.configs = ModelsConfig.PROVIDERS;
        this.conversationRepository = new InMemoryConversationRepository();
        this.memoryStore = new HistoryAwareChatMemoryStore(conversationRepository);
        this.tokenizer = new OpenAiTokenizer("gpt-4");
        this.currentIndex = 0;
        this.currentAssistant = createAssistant(configs.get(currentIndex));
    }
    
    private Assistant createAssistant(ProviderConfig config) {
        ChatLanguageModel model = ModelFactory.createModel(config);
        
        ChatMemoryProvider memoryProvider = memoryId -> SummarizingTokenWindowChatMemory.builder()
            .id(memoryId)
            .maxTokens(MAX_TOKENS)
            .tokenizer(tokenizer)
            .chatMemoryStore(memoryStore)
            .chatLanguageModel(model)
            .build();
        
        return AiServices.builder(Assistant.class)
            .chatLanguageModel(model)
            .chatMemoryProvider(memoryProvider)
            .build();
    }
    
    public void run() {
        Scanner scanner = new Scanner(System.in);
        
        printWelcome();
        
        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                continue;
            }
            
            if (input.equalsIgnoreCase("/quit") || input.equalsIgnoreCase("/exit")) {
                System.out.println("Goodbye!");
                break;
            }
            
            if (input.equalsIgnoreCase("/setmodel")) {
                handleSetModel(scanner);
                continue;
            }
            
            if (input.equalsIgnoreCase("/clear")) {
                handleClear();
                continue;
            }
            
            if (input.equalsIgnoreCase("/help")) {
                printHelp();
                continue;
            }
            
            if (input.startsWith("/")) {
                System.out.println("Unknown command. Type /help for available commands.");
                continue;
            }
            
            handleChat(input);
        }
        
        scanner.close();
    }
    
    private void printWelcome() {
        System.out.println("===========================================");
        System.out.println("   AI Chatbot CLI - LangChain4j Demo");
        System.out.println("===========================================");
        System.out.println();
        printHelp();
        System.out.println();
        System.out.println("Current model: " + configs.get(currentIndex).getDisplayName());
    }
    
    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  /setmodel  - Switch AI model");
        System.out.println("  /clear     - Clear conversation history");
        System.out.println("  /help      - Show this help");
        System.out.println("  /quit      - Exit application");
    }
    
    private void handleSetModel(Scanner scanner) {
        System.out.println("\nAvailable models:");
        for (int i = 0; i < configs.size(); i++) {
            String marker = (i == currentIndex) ? " [current]" : "";
            System.out.println("  " + (i + 1) + ". " + configs.get(i).getDisplayName() + marker);
        }
        
        System.out.print("\nSelect model (1-" + configs.size() + "): ");
        String choice = scanner.nextLine().trim();
        
        try {
            int index = Integer.parseInt(choice) - 1;
            if (index >= 0 && index < configs.size()) {
                currentIndex = index;
                currentAssistant = createAssistant(configs.get(currentIndex));
                System.out.println("Switched to: " + configs.get(currentIndex).getDisplayName());
                System.out.println("(Conversation history preserved)");
            } else {
                System.out.println("Invalid selection. Please choose 1-" + configs.size());
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Please enter a number.");
        }
    }
    
    private void handleClear() {
        // Clear the repository and recreate assistant
        memoryStore.deleteMessages(MEMORY_ID);
        currentAssistant = createAssistant(configs.get(currentIndex));
        System.out.println("Conversation history cleared. Starting fresh!");
    }
    
    private void handleChat(String message) {
        String providerName = configs.get(currentIndex).getDisplayName().split(" ")[0];
        System.out.println();
        System.out.print(providerName + ": ");
        
        try {
            String response = currentAssistant.chat(MEMORY_ID, message);
            System.out.println(response);
        } catch (Exception e) {
            System.out.println("[Error] " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        ChatApplication app = new ChatApplication();
        app.run();
    }
}
