package com.mujin.utils;

import java.io.InputStream;
import java.security.MessageDigest;

public class FileHashUtils {

    private FileHashUtils() {}

    /**
     * 流式计算 SHA-256，避免大文件一次性进入内存。
     */
    public static String calculateSha256(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            byte[] hashBytes = digest.digest();
            StringBuilder hex = new StringBuilder();

            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }

            return hex.toString();

        } catch (Exception e) {
            throw new RuntimeException("计算文件 SHA-256 失败", e);
        }
    }
}