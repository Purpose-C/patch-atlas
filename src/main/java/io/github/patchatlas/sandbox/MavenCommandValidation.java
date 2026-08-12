package io.github.patchatlas.sandbox;

/** Maven 模块路径与 test selector 白名单校验（命令构造与 Run 提交共用）。 */
public final class MavenCommandValidation {

    private static final String SAFE_MODULE_SEGMENT = "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}";
    private static final String SAFE_TEST_SELECTOR =
            "[A-Za-z_$][A-Za-z0-9_.$]*(#[A-Za-z_$][A-Za-z0-9_$]*)?";

    private MavenCommandValidation() {}

    public static void requireSafeModulePath(String modulePath) {
        if (modulePath == null) {
            throw new IllegalArgumentException("modulePath is required");
        }
        if (modulePath.length() > 512) {
            throw new IllegalArgumentException("modulePath must not exceed 512 characters");
        }
        if (modulePath.isEmpty()) {
            return;
        }
        for (String segment : modulePath.split("/", -1)) {
            if (!segment.matches(SAFE_MODULE_SEGMENT)) {
                throw new IllegalArgumentException("modulePath contains an unsafe segment");
            }
        }
    }

    public static void requireSafeTestSelector(String testSelector) {
        if (testSelector == null
                || testSelector.length() > 256
                || !testSelector.matches(SAFE_TEST_SELECTOR)) {
            throw new IllegalArgumentException("testSelector must be a class or class#method selector");
        }
    }
}
