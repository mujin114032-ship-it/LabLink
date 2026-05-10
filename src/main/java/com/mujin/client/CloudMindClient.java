package com.mujin.client;

import com.mujin.config.CloudMindProperties;
import com.mujin.domain.dto.cloudmind.CloudMindFileDeleteSyncRequest;
import com.mujin.domain.dto.cloudmind.CloudMindFileSyncRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudMindClient {

    private final CloudMindProperties cloudMindProperties;

    private final RestTemplate restTemplate = new RestTemplate();

    public void syncFile(CloudMindFileSyncRequest request) {
        String url = cloudMindProperties.getBaseUrl() + "/api/internal/lablink/files/sync";

        try {
            HttpEntity<CloudMindFileSyncRequest> entity = new HttpEntity<>(request, buildHeaders());

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("CloudMind 文件同步请求成功，labLinkFileId={}, fileName={}",
                        request.getLabLinkFileId(), request.getFileName());
            } else {
                log.warn("CloudMind 文件同步非 2xx 响应，labLinkFileId={}, status={}, body={}",
                        request.getLabLinkFileId(), response.getStatusCode(), response.getBody());
            }

        } catch (Exception e) {
            // 注意：不能影响 LabLink 主流程
            log.warn("CloudMind 文件同步失败，不影响 LabLink 主流程，labLinkFileId={}, fileName={}",
                    request.getLabLinkFileId(), request.getFileName(), e);
        }
    }

    public void deleteSync(CloudMindFileDeleteSyncRequest request) {
        String url = cloudMindProperties.getBaseUrl() + "/api/internal/lablink/files/delete-sync";

        try {
            HttpEntity<CloudMindFileDeleteSyncRequest> entity = new HttpEntity<>(request, buildHeaders());

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("CloudMind 文件删除同步请求成功，labLinkFileId={}",
                        request.getLabLinkFileId());
            } else {
                log.warn("CloudMind 文件删除同步非 2xx 响应，labLinkFileId={}, status={}, body={}",
                        request.getLabLinkFileId(), response.getStatusCode(), response.getBody());
            }

        } catch (Exception e) {
            // 注意：不能影响 LabLink 主流程
            log.warn("CloudMind 文件删除同步失败，不影响 LabLink 主流程，labLinkFileId={}",
                    request.getLabLinkFileId(), e);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", cloudMindProperties.getInternalToken());
        return headers;
    }
}