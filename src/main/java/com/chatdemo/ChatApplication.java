package com.chatdemo;

import com.chatdemo.config.ModelsConfig;
import com.chatdemo.memory.HistoryAwareChatMemoryStore;
import com.chatdemo.memory.SummarizingTokenWindowChatMemory;
import com.chatdemo.repository.ConversationRepository;
import com.chatdemo.repository.InMemoryConversationRepository;
import com.chatdemo.tools.ImageGenerationTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.image.DisabledImageModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
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
    private final TokenCountEstimator tokenCountEstimator;
    private final ImageGenerationTool imageTool;
    private final String defaultOpenAiImageModel;
    private final String defaultGeminiImageModel;
    private int currentIndex;
    private Assistant currentAssistant;
    private String currentImageProvider;
    private String currentOpenAiImageModel;
    private String currentGeminiImageModel;
    
    /**
     * AI Assistant interface - LangChain4j will implement this.
     */
    interface Assistant {
        @SystemMessage("""
            You are a helpful assistant.
            If the user asks to create, draw, generate, or edit images, call the image tool.
            When an image URL is provided and the user wants modifications, use the edit tool.
            Return the tool output directly to the user.
            """)
        String chat(@MemoryId String memoryId, @UserMessage String message);
    }
    
    public ChatApplication() {
        this.configs = ModelsConfig.PROVIDERS;
        this.conversationRepository = new InMemoryConversationRepository();
        this.memoryStore = new HistoryAwareChatMemoryStore(conversationRepository);
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4");
        this.defaultOpenAiImageModel = ModelFactory.defaultOpenAiImageModel();
        this.defaultGeminiImageModel = ModelFactory.defaultGeminiImageModel();
        this.currentOpenAiImageModel = defaultOpenAiImageModel;
        this.currentGeminiImageModel = defaultGeminiImageModel;
        this.currentImageProvider = "openai";
        this.imageTool = new ImageGenerationTool();
        this.currentIndex = 0;
        this.currentAssistant = createAssistant(configs.get(currentIndex));
    }
    
    private Assistant createAssistant(ProviderConfig config) {
        ChatModel model = ModelFactory.createModel(config);
        refreshImageModels();
        
        ChatMemoryProvider memoryProvider = memoryId -> SummarizingTokenWindowChatMemory.builder()
            .id(memoryId)
            .maxTokens(MAX_TOKENS)
            .tokenCountEstimator(tokenCountEstimator)
            .chatMemoryStore(memoryStore)
            .chatModel(model)
            .build();
        
        return AiServices.builder(Assistant.class)
            .chatModel(model)
            .chatMemoryProvider(memoryProvider)
            .tools(imageTool)
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
            
            if (input.toLowerCase().startsWith("/imagemodel")) {
                handleImageModel(input);
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
        System.out.println("  /imagemodel - Switch image model (openai|gemini)");
        System.out.println("  /clear     - Clear conversation history");
        System.out.println("  /help      - Show this help");
        System.out.println("  /quit      - Exit application");
    }

    private void handleImageModel(String input) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length == 1) {
            printImageModelStatus();
            return;
        }
        
        String provider = parts[1].toLowerCase();
        String modelName = (parts.length >= 3) ? parts[2] : null;
        
        switch (provider) {
            case "openai", "dall-e", "dalle" -> {
                if (modelName != null && !modelName.isBlank()) {
                    currentOpenAiImageModel = modelName;
                }
                currentImageProvider = "openai";
                imageTool.setProvider(ImageGenerationTool.Provider.OPENAI);
                refreshImageModels();
                System.out.println("Image model set to OpenAI (" + imageTool.getOpenAiModelName() + ")");
            }
            case "gemini" -> {
                if (modelName != null && !modelName.isBlank()) {
                    currentGeminiImageModel = modelName;
                }
                currentImageProvider = "gemini";
                imageTool.setProvider(ImageGenerationTool.Provider.GEMINI);
                refreshImageModels();
                System.out.println("Image model set to Gemini (" + imageTool.getGeminiModelName() + ")");
            }
            default -> {
                System.out.println("Unknown image model provider. Use /imagemodel openai or /imagemodel gemini.");
                System.out.println("Examples:");
                System.out.println("  /imagemodel openai");
                System.out.println("  /imagemodel gemini");
                System.out.println("  /imagemodel gemini " + defaultGeminiImageModel);
            }
        }
    }
    
    private void printImageModelStatus() {
        System.out.println("Image model providers:");
        System.out.println("  OpenAI: " + currentOpenAiImageModel);
        System.out.println("  Gemini: " + currentGeminiImageModel);
        System.out.println("Current image provider: " + currentImageProvider + " (" + imageTool.getCurrentModelName() + ")");
        System.out.println("Examples:");
        System.out.println("  /imagemodel openai");
        System.out.println("  /imagemodel gemini");
        System.out.println("  /imagemodel gemini " + defaultGeminiImageModel);
    }
    
    private void refreshImageModels() {
        String openAiKey = ModelsConfig.getOpenAiKey();
        ImageModel openAiModel = (openAiKey == null || openAiKey.isBlank())
            ? new DisabledImageModel()
            : ModelFactory.createImageModel(openAiKey, currentOpenAiImageModel);
        
        String geminiKey = ModelsConfig.getGeminiKey();
        ChatModel geminiModel = (geminiKey == null || geminiKey.isBlank())
            ? null
            : ModelFactory.createGeminiImageModel(geminiKey, currentGeminiImageModel);
        
        imageTool.setOpenAiImageModel(openAiModel, currentOpenAiImageModel);
        imageTool.setGeminiImageModel(geminiModel, currentGeminiImageModel);
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
