package com.chatdemo.config;

/**
 * Configuration for Firebase Storage uploads.
 */
public final class FirebaseConfig {

    private static final String DEFAULT_SERVICE_ACCOUNT_PATH = "firebase_settings.json";

    private FirebaseConfig() {
    }

    public static String getServiceAccountPath() {
        String path = System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH");
        if (path != null && !path.isBlank()) {
            return path;
        }
        return DEFAULT_SERVICE_ACCOUNT_PATH;
    }

    public static String getStorageBucket() {
        String bucket = System.getenv("FIREBASE_STORAGE_BUCKET");
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        return null;
    }
}
