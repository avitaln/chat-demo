package com.chatdemo.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GeneratedImageHelper;
import dev.langchain4j.model.image.DisabledImageModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * Tool for image generation and editing using OpenAI or Gemini.
 */
public class ImageGenerationTool {

    public enum Provider {
        OPENAI,
        GEMINI
    }

    private Provider provider = Provider.OPENAI;
    private ImageModel openAiImageModel = new DisabledImageModel();
    private ChatModel geminiImageModel;
    private String openAiModelName = "dall-e-3";
    private String geminiModelName = "gemini-2.5-flash-image";

    public void setOpenAiImageModel(ImageModel model, String modelName) {
        this.openAiImageModel = model;
        this.openAiModelName = modelName;
    }

    public void setGeminiImageModel(ChatModel model, String modelName) {
        this.geminiImageModel = model;
        this.geminiModelName = modelName;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getCurrentModelName() {
        return provider == Provider.GEMINI ? geminiModelName : openAiModelName;
    }

    public String getOpenAiModelName() {
        return openAiModelName;
    }

    public String getGeminiModelName() {
        return geminiModelName;
    }

    @Tool("Generate an image from a text description. Use when the user asks to draw, create, or generate an image.")
    public String generateImage(@P("Detailed description of the image to generate") String prompt) {
        try {
            return provider == Provider.GEMINI
                ? generateWithGemini(prompt)
                : generateWithOpenAi(prompt);
        } catch (Exception e) {
            return "[Image Error] " + e.getMessage();
        }
    }

    @Tool("Edit or transform an existing image using a provided image URL. Use when the user supplies an image URL and asks to modify it.")
    public String editImage(
        @P("URL of the source image to edit") String imageUrl,
        @P("Detailed description of the desired edits") String prompt
    ) {
        try {
            if (provider == Provider.GEMINI) {
                return "[Image Error] Gemini image edits from URL are not supported in this CLI yet. Use /imagemodel openai.";
            }
            if (openAiImageModel instanceof DisabledImageModel) {
                return "[Image Error] Image editing is disabled. Set OPENAI_API_KEY to enable it.";
            }
            Image input = Image.builder().url(imageUrl).build();
            Response<Image> response = openAiImageModel.edit(input, prompt);
            return formatImageResponse(response);
        } catch (Exception e) {
            return "[Image Error] " + e.getMessage();
        }
    }

    private String generateWithOpenAi(String prompt) {
        if (openAiImageModel instanceof DisabledImageModel) {
            return "[Image Error] Image generation is disabled. Set OPENAI_API_KEY to enable it.";
        }
        Response<Image> response = openAiImageModel.generate(prompt);
        return formatImageResponse(response);
    }

    private String generateWithGemini(String prompt) {
        if (geminiImageModel == null) {
            return "[Image Error] Gemini image generation is disabled. Set GEMINI_API_KEY to enable it.";
        }
        ChatResponse response = geminiImageModel.chat(UserMessage.from(prompt));
        AiMessage aiMessage = response.aiMessage();
        if (!GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
            return "[Image Error] Gemini did not return an image.";
        }
        List<Image> images = GeneratedImageHelper.getGeneratedImages(aiMessage);
        if (images.isEmpty()) {
            return "[Image Error] Gemini returned no images.";
        }
        return formatImage(images.get(0));
    }

    private String formatImageResponse(Response<Image> response) {
        Image image = response.content();
        if (image == null) {
            return "[Image Error] No image returned.";
        }
        return formatImage(image);
    }

    private String formatImage(Image image) {
        if (image.url() != null) {
            return "Image URL: " + image.url();
        }
        if (image.base64Data() != null) {
            String mimeType = image.mimeType() != null ? image.mimeType() : "image/png";
            return "Image URL: data:" + mimeType + ";base64," + image.base64Data();
        }
        return "[Image Error] Image response did not include a URL or base64 data.";
    }
}
