package com.chatdemo.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.util.Strings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Acl;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.StorageClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Uploads images to Firebase Storage and returns public URLs.
 */
public class FirebaseStorageUploader {

    private final Path serviceAccountPath;
    private final String configuredBucket;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FirebaseStorageUploader(String serviceAccountPath, String configuredBucket) {
        this.serviceAccountPath = Path.of(serviceAccountPath);
        this.configuredBucket = configuredBucket;
    }

    public boolean isConfigured() {
        return Files.exists(serviceAccountPath);
    }

    public String uploadBase64(byte[] bytes, String mimeType, String provider, String modelName) {
        try {
            initialize();
            Bucket bucket = StorageClient.getInstance().bucket();
            String objectPath = buildObjectPath(provider, modelName, mimeType);
            Blob blob = bucket.create(objectPath, bytes, mimeType);
            blob.createAcl(Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER));
            return "https://storage.googleapis.com/" + bucket.getName() + "/" + objectPath;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload image: " + e.getMessage(), e);
        }
    }

    private void initialize() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }
        try (InputStream inputStream = new FileInputStream(serviceAccountPath.toFile())) {
            FirebaseOptions.Builder builder = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(inputStream));
            String bucketName = resolveBucketName();
            if (!Strings.isNullOrEmpty(bucketName)) {
                builder.setStorageBucket(bucketName);
            }
            FirebaseApp.initializeApp(builder.build());
        }
    }

    private String resolveBucketName() {
        if (!Strings.isNullOrEmpty(configuredBucket)) {
            return configuredBucket;
        }
        try {
            JsonNode root = objectMapper.readTree(serviceAccountPath.toFile());
            JsonNode projectId = root.get("project_id");
            if (projectId != null && !projectId.asText().isBlank()) {
                return projectId.asText() + ".appspot.com";
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private String buildObjectPath(String provider, String modelName, String mimeType) {
        String safeProvider = sanitize(provider);
        String safeModel = sanitize(modelName);
        String extension = extensionForMimeType(mimeType);
        return "images/" + safeProvider + "/" + safeModel + "/" + UUID.randomUUID() + extension;
    }

    private String sanitize(String input) {
        if (input == null) {
            return "unknown";
        }
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }

    private String extensionForMimeType(String mimeType) {
        if (mimeType == null) {
            return ".png";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            default -> ".png";
        };
    }
}
