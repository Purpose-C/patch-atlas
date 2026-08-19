package io.github.patchatlas.benchmark;

import io.github.patchatlas.run.ContextOrigin;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 薄 wrapper：只接受封闭动作，不接受任意 shell、任意 caseId 或动态选择表达式。
 *
 * <p>协议九个：{@code freeze / calibrate / calibrate-1 / calibrate-2 / calibrate-3 /
 * agent-4 / agent-5 / agent-6 / verify}。单例 {@code calibrate-N} 让第一例充当金丝雀。
 * {@code dry-run} 仍是 DIAGNOSTIC 入口，不进入正式分母。
 * {@code dry-run-text} / {@code dry-run-graph} 用同一诊断案例分别走文本工具与图工具定位。
 * {@code arm-heuristic} / {@code arm-text} / {@code arm-graph} 对冻结队列全部六例以
 * {@code AGENT_BENCHMARK} 启动，彼此只差定位来源。
 * {@code case-study-heuristic} / {@code case-study-text} / {@code case-study-graph}
 * 对已确认的单案例以 {@code evaluation_id=spring-case-study} 启动，不改 {@code case_id}，
 * 也不写入 {@code batch5-three-arm/}、{@code batch5b-three-arm/} 或 {@code task018/}。
 * {@code verify-three-arm} 从 PostgreSQL 读取 18 次终态 Run，把证据写到
 * {@code benchmark-cases/batch5b-three-arm/}，不写 {@code task018} 或 {@code batch5-three-arm}。
 * {@code verify-three-arm-036} 按 {@code evaluation_id} 读取 036 的 18 次 Run，重导到临时目录并
 * 与冻结的 {@code results.json} 比对，不改 {@code batch5-three-arm/}。
 * 三臂查找用 {@code evaluation_id} 消歧，不改会被打印的 {@code case_id}。
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
    public static final String VERIFY_THREE_ARM = "verify-three-arm";
    public static final String VERIFY_THREE_ARM_036 = "verify-three-arm-036";
    public static final String DRY_RUN = "dry-run";
    public static final String DRY_RUN_TEXT = "dry-run-text";
    public static final String DRY_RUN_GRAPH = "dry-run-graph";
    public static final String ARM_HEURISTIC = "arm-heuristic";
    public static final String ARM_TEXT = "arm-text";
    public static final String ARM_GRAPH = "arm-graph";
    public static final String CASE_STUDY_HEURISTIC = "case-study-heuristic";
    public static final String CASE_STUDY_TEXT = "case-study-text";
    public static final String CASE_STUDY_GRAPH = "case-study-graph";

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
            VERIFY_THREE_ARM,
            VERIFY_THREE_ARM_036,
            DRY_RUN,
            DRY_RUN_TEXT,
            DRY_RUN_GRAPH,
            ARM_HEURISTIC,
            ARM_TEXT,
            ARM_GRAPH,
            CASE_STUDY_HEURISTIC,
            CASE_STUDY_TEXT,
            CASE_STUDY_GRAPH);

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

    /**
     * 校验动作是否为正式运行（calibrate / calibrate-N / agent-N / arm-*），
     * 需要真实模型和 Docker。诊断干跑不在此列。
     */
    public static boolean isFormalRun(String action) {
        String parsed = parseAction(action);
        return !FREEZE.equals(parsed)
                && !VERIFY.equals(parsed)
                && !VERIFY_THREE_ARM.equals(parsed)
                && !VERIFY_THREE_ARM_036.equals(parsed)
                && !parsed.startsWith("dry-run");
    }

    /** 干跑与三臂动作所选择的定位来源；其余动作没有该因子。 */
    public static ContextOrigin locatingOrigin(String action) {
        return switch (parseAction(action)) {
            case DRY_RUN, ARM_HEURISTIC, CASE_STUDY_HEURISTIC -> ContextOrigin.HEURISTIC;
            case DRY_RUN_TEXT, ARM_TEXT, CASE_STUDY_TEXT -> ContextOrigin.TEXT_TOOLS;
            case DRY_RUN_GRAPH, ARM_GRAPH, CASE_STUDY_GRAPH -> ContextOrigin.GRAPH_TOOLS;
            default -> throw new IllegalArgumentException(action + " has no locating origin");
        };
    }

    /** 三臂动作所选择的评测批次；该值写入 {@code evaluation_id}，不进入证据报告。 */
    public static String threeArmEvaluationId(String action) {
        return switch (parseAction(action)) {
            case VERIFY_THREE_ARM, ARM_HEURISTIC, ARM_TEXT, ARM_GRAPH -> EvaluationIds.BATCH5B_THREE_ARM;
            case VERIFY_THREE_ARM_036 -> EvaluationIds.BATCH5_THREE_ARM;
            case CASE_STUDY_HEURISTIC, CASE_STUDY_TEXT, CASE_STUDY_GRAPH ->
                    EvaluationIds.SPRING_CASE_STUDY;
            default -> throw new IllegalArgumentException(action + " has no three-arm evaluation");
        };
    }

    /** 已确认单案例的三臂动作；不遍历冻结队列。 */
    public static boolean isCaseStudy(String action) {
        String parsed = parseAction(action);
        return CASE_STUDY_HEURISTIC.equals(parsed)
                || CASE_STUDY_TEXT.equals(parsed)
                || CASE_STUDY_GRAPH.equals(parsed);
    }
}
