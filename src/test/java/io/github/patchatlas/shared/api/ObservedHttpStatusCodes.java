package io.github.patchatlas.shared.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ObservedHttpStatusCodes {

    static final Pattern STATUS_IS_NUMBER = Pattern.compile("status\\(\\)\\.is\\((\\d+)\\)");
    static final Pattern JSON_STATUS = Pattern.compile("jsonPath\\(\"\\$\\.status\"\\)\\.value\\((\\d+)\\)");
    static final Pattern REST_ASSURED_STATUS_CODE = Pattern.compile("statusCode\\((\\d+)\\)");
    static final Map<String, Integer> MVC_STATUS_MATCHERS = Map.of(
            "status().isOk()", 200,
            "status().isAccepted()", 202,
            "status().isBadRequest()", 400,
            "status().isNotFound()", 404,
            "status().isConflict()", 409,
            "status().isServiceUnavailable()", 503);

    private ObservedHttpStatusCodes() {}

    static Set<Integer> fromTestTree(Path root) throws Exception {
        Set<Integer> codes = new TreeSet<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(candidate -> candidate.toString().endsWith("Test.java")).toList()) {
                codes.addAll(fromSource(Files.readString(path)));
            }
        }
        return codes;
    }

    static Set<Integer> fromSource(String text) {
        Set<Integer> codes = new TreeSet<>();
        if (!text.contains("status().") && !text.contains("statusCode(")) {
            return codes;
        }
        for (Map.Entry<String, Integer> matcher : MVC_STATUS_MATCHERS.entrySet()) {
            if (text.contains(matcher.getKey())) {
                codes.add(matcher.getValue());
            }
        }
        Matcher numbered = STATUS_IS_NUMBER.matcher(text);
        while (numbered.find()) {
            codes.add(Integer.parseInt(numbered.group(1)));
        }
        Matcher jsonStatus = JSON_STATUS.matcher(text);
        while (jsonStatus.find()) {
            codes.add(Integer.parseInt(jsonStatus.group(1)));
        }
        Matcher restAssured = REST_ASSURED_STATUS_CODE.matcher(text);
        while (restAssured.find()) {
            codes.add(Integer.parseInt(restAssured.group(1)));
        }
        return codes;
    }
}
