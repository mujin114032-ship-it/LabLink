package com.mujin.service.support;

import com.mujin.config.CloudMindProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AgentFileTypeSupport {

    private final CloudMindProperties cloudMindProperties;

    public boolean isSupported(String fileName) {
        String ext = extractFileType(fileName);
        return ext != null && cloudMindProperties.getSupportedFileTypes().contains(ext);
    }

    public String extractFileType(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        int dotIndex = fileName.lastIndexOf(".");
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    public String guessContentType(String fileName) {
        String ext = extractFileType(fileName);

        if (ext == null) {
            return "application/octet-stream";
        }

        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "html", "htm" -> "text/html";
            default -> "application/octet-stream";
        };
    }
}