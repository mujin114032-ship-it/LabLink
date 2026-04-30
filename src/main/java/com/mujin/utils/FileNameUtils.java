package com.mujin.utils;

import java.time.LocalDate;

public class FileNameUtils {

    private FileNameUtils() {}

    public static String generateObjectName(String originalName, String identifier) {
        String ext = "";

        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }

        String dateStr = LocalDate.now().toString().replace("-", "/");
        return dateStr + "/" + identifier + ext;
    }
}