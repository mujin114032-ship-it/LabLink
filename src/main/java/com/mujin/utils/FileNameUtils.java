package com.mujin.utils;

import java.time.LocalDate;
import java.util.Set;

public class FileNameUtils {

    private FileNameUtils() {}

    /**
     * 生成对象名称
     * 格式：日期/标识符.扩展名
     * 例如：2023/12/25/1234567890abcdef.jpg
     * @param originalName 原始文件名
     * @param identifier 标识符（例如：用户 ID）
     * @return 生成的对象名称
     */
    public static String generateObjectName(String originalName, String identifier) {
        String ext = "";

        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }

        String dateStr = LocalDate.now().toString().replace("-", "/");
        return dateStr + "/" + identifier + ext;
    }

    /**
     * 构造 ZIP 内部文件名，避免重复文件名覆盖，同时去掉路径分隔符。
     */
    public static String buildUniqueZipEntryName(String originalName, Set<String> usedEntryNames) {
        String safeName = sanitizeZipEntryName(originalName);

        if (!usedEntryNames.contains(safeName)) {
            usedEntryNames.add(safeName);
            return safeName;
        }

        String name = safeName;
        String ext = "";

        int dotIndex = safeName.lastIndexOf(".");
        if (dotIndex > 0) {
            name = safeName.substring(0, dotIndex);
            ext = safeName.substring(dotIndex);
        }

        int index = 1;
        String candidate;

        do {
            candidate = name + "(" + index + ")" + ext;
            index++;
        } while (usedEntryNames.contains(candidate));

        usedEntryNames.add(candidate);
        return candidate;
    }

    /**
     * 防止 ZIP Entry 中出现路径穿越字符。
     */
    private static String sanitizeZipEntryName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "未命名文件";
        }

        return name
                .replace("\\", "_")
                .replace("/", "_")
                .replace("..", "_")
                .trim();
    }
}