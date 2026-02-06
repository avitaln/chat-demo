package com.chatdemo.tools;

import com.chatdemo.storage.FirebaseStorageUploader;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GeneratedImageHelper;
import dev.langchain4j.model.image.DisabledImageModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.List;

/**
 * Tool for image generation and editing using OpenAI or Gemini.
 */
public class ImageGenerationTool {

    public enum Provider {
        OPENAI,
        GEMINI,
        GROK
    }

    private Provider provider = Provider.OPENAI;
    private ImageModel openAiImageModel = new DisabledImageModel();
    private ImageModel grokImageModel = new DisabledImageModel();
    private ChatModel geminiImageModel;
    private FirebaseStorageUploader firebaseStorageUploader;
    private String openAiModelName = "chatgpt-image-latest";
    private String geminiModelName = "gemini-2.5-flash-image";
    private String grokModelName = "grok-imagine-image";
    private boolean imageToolsEnabled = true;
    private String imageToolsDisabledReason = "Image generation is disabled for the current model.";

    public void setOpenAiImageModel(ImageModel model, String modelName) {
        this.openAiImageModel = model;
        this.openAiModelName = modelName;
    }

    public void setGeminiImageModel(ChatModel model, String modelName) {
        this.geminiImageModel = model;
        this.geminiModelName = modelName;
    }

    public void setGrokImageModel(ImageModel model, String modelName) {
        this.grokImageModel = model;
        this.grokModelName = modelName;
    }

    public void setFirebaseStorageUploader(FirebaseStorageUploader uploader) {
        this.firebaseStorageUploader = uploader;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getCurrentModelName() {
        return switch (provider) {
            case GEMINI -> geminiModelName;
            case GROK -> grokModelName;
            case OPENAI -> openAiModelName;
        };
    }

    public String getOpenAiModelName() {
        return openAiModelName;
    }

    public String getGeminiModelName() {
        return geminiModelName;
    }

    public String getGrokModelName() {
        return grokModelName;
    }

    public void disableImageTools(String reason) {
        this.imageToolsEnabled = false;
        this.imageToolsDisabledReason = (reason == null || reason.isBlank())
            ? "Image generation is disabled for the current model."
            : reason;
    }

    public void enableImageTools() {
        this.imageToolsEnabled = true;
        this.imageToolsDisabledReason = "Image generation is disabled for the current model.";
    }

    @Tool("Generate an image from a text description. Use when the user asks to draw, create, or generate an image.")
    public String generateImage(@P("Detailed description of the image to generate") String prompt) {
        try {
            if (!imageToolsEnabled) {
                return "[Image Error] " + imageToolsDisabledReason;
            }
            return switch (provider) {
                case GEMINI -> generateWithGemini(prompt);
                case GROK -> generateWithGrok(prompt);
                case OPENAI -> generateWithOpenAi(prompt);
            };
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
            if (!imageToolsEnabled) {
                return "[Image Error] " + imageToolsDisabledReason;
            }
            if (provider == Provider.GEMINI) {
                if (geminiImageModel == null) {
                    return "[Image Error] Gemini image generation is disabled. Set GEMINI_API_KEY to enable it.";
                }
                ImagePayload payload = downloadImage(imageUrl);
                String base64 = Base64.getEncoder().encodeToString(payload.bytes);
                ChatResponse response = geminiImageModel.chat(UserMessage.from(
                    ImageContent.from(base64, payload.mimeType),
                    TextContent.from(prompt)
                ));
                AiMessage aiMessage = response.aiMessage();
                if (!GeneratedImageHelper.hasGeneratedImages(aiMessage)) {
                    return "[Image Error] Gemini did not return an image. Try /imagemodel gemini gemini-2.5-flash-image.";
                }
                List<Image> images = GeneratedImageHelper.getGeneratedImages(aiMessage);
                if (images.isEmpty()) {
                    return "[Image Error] Gemini returned no images.";
                }
                return formatImage(images.get(0));
            }
            if (provider == Provider.GROK) {
                if (grokImageModel instanceof DisabledImageModel) {
                    return "[Image Error] Grok image editing is disabled. Set XAI_API_KEY to enable it.";
                }
                Image input = Image.builder().url(imageUrl).build();
                Response<Image> response = grokImageModel.edit(input, prompt);
                return formatImageResponse(response);
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

    private String generateWithGrok(String prompt) {
        if (grokImageModel instanceof DisabledImageModel) {
            return "[Image Error] Grok image generation is disabled. Set XAI_API_KEY to enable it.";
        }
        Response<Image> response = grokImageModel.generate(prompt);
        return formatImageResponse(response);
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
            if (firebaseStorageUploader != null && firebaseStorageUploader.isConfigured()) {
                byte[] bytes = Base64.getDecoder().decode(image.base64Data());
                String providerName = provider.name().toLowerCase();
                String modelName = getCurrentModelName();
                String url = firebaseStorageUploader.uploadBase64(bytes, mimeType, providerName, modelName);
                return "Image URL: " + url;
            }
            return "Image URL: data:" + mimeType + ";base64," + image.base64Data();
        }
        return "[Image Error] Image response did not include a URL or base64 data.";
    }

    private ImagePayload downloadImage(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "chat-demo");
        int status = connection.getResponseCode();
        InputStream inputStream = (status >= 200 && status < 300)
            ? connection.getInputStream()
            : connection.getErrorStream();
        if (inputStream == null) {
            throw new IOException("Unable to download image: HTTP " + status);
        }
        byte[] bytes = readAllBytes(inputStream);
        String mimeType = connection.getContentType();
        if (mimeType != null) {
            int separator = mimeType.indexOf(';');
            if (separator > 0) {
                mimeType = mimeType.substring(0, separator).trim();
            } else {
                mimeType = mimeType.trim();
            }
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = guessMimeType(imageUrl);
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "image/png";
        }
        return new ImagePayload(bytes, mimeType);
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        try (inputStream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private String guessMimeType(String imageUrl) {
        String lower = imageUrl.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return null;
    }

    private static class ImagePayload {
        private final byte[] bytes;
        private final String mimeType;

        private ImagePayload(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }
}
