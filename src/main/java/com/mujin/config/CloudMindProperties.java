package com.mujin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "cloudmind")
public class CloudMindProperties {

    private String baseUrl;

    private String internalToken;

    private List<String> supportedFileTypes = List.of(
            "pdf", "doc", "docx", "txt", "md", "html"
    );
}
