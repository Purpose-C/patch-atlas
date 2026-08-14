package io.github.patchatlas.benchmark;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 薄 wrapper：只接受封闭动作，不接受任意 shell、任意 caseId 或动态选择表达式。
 *
 * <p>协议九个：{@code freeze / calibrate / calibrate-1 / calibrate-2 / calibrate-3 /
 * agent-4 / agent-5 / agent-6 / verify}。单例 {@code calibrate-N} 让第一例充当金丝雀。
 * {@code dry-run} 仍是 DIAGNOSTIC 入口，不进入正式分母。
 * 缺少明确前提时显式入口失败，不能悄悄 skip 并报告成功。
 */
public final class BenchmarkActions {

    public static final String FREEZE = "freeze";
    public static final String CALIBRATE = "calibrate";
    public static final String CALIBRATE_1 = "calibrate-1";
    public static final String CALIBRATE_2 = "calibrate-2";
    public static final String CALIBRATE_3 = "calibrate-3";
    public static final String AGENT_4 = "agent-4";
    public static final String AGENT_5 = "agent-5";
    public static final String AGENT_6 = "agent-6";
    public static final String VERIFY = "verify";
    public static final String DRY_RUN = "dry-run";

    private static final Set<String> CLOSED_ACTIONS = Set.of(
            FREEZE,
            CALIBRATE,
            CALIBRATE_1,
            CALIBRATE_2,
            CALIBRATE_3,
            AGENT_4,
            AGENT_5,
            AGENT_6,
            VERIFY,
            DRY_RUN);

    private BenchmarkActions() {}

    /** 校验动作是否在封闭集合内；不在则抛出，绝不静默接受。 */
    public static String parseAction(String input) {
        Objects.requireNonNull(input, "input");
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (!CLOSED_ACTIONS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "unknown benchmark action '" + input + "'; expected one of " + CLOSED_ACTIONS);
        }
        return normalized;
    }

    /** Agent 动作对应的 cohort 位置（4–6）。 */
    public static int agentPosition(String action) {
        return switch (parseAction(action)) {
            case AGENT_4 -> 4;
            case AGENT_5 -> 5;
            case AGENT_6 -> 6;
            default -> throw new IllegalArgumentException(action + " is not an agent action");
        };
    }

    /** 单例 Calibration 动作对应的 cohort 位置（1–3）。 */
    public static int calibratePosition(String action) {
        return switch (parseAction(action)) {
            case CALIBRATE_1 -> 1;
            case CALIBRATE_2 -> 2;
            case CALIBRATE_3 -> 3;
            default -> throw new IllegalArgumentException(
                    action + " is not a single-case calibration action");
        };
    }

    /** 校验动作是否为正式运行（calibrate / calibrate-N / agent-N），需要真实模型和 Docker。 */
    public static boolean isFormalRun(String action) {
        String parsed = parseAction(action);
        return !FREEZE.equals(parsed) && !VERIFY.equals(parsed) && !DRY_RUN.equals(parsed);
    }
}
