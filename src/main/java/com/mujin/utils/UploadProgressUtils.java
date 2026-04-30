package com.mujin.utils;

import java.util.List;

public class UploadProgressUtils {

    private UploadProgressUtils() {}

    public static int calculateProgress(List<Integer> uploadedChunks, Integer totalChunks) {
        if (totalChunks == null || totalChunks <= 0) {
            return 0;
        }

        int uploadedCount = uploadedChunks == null ? 0 : uploadedChunks.size();
        return Math.min(100, (int) Math.floor(uploadedCount * 100.0 / totalChunks));
    }
}