package io.github.patchatlas.benchmark;

/**
 * Values stored on {@code verification_run.evaluation_id}. They disambiguate
 * {@code findRunByCase} when two benchmark batches share a case id. They are
 * not copied into evidence reports.
 */
public final class EvaluationIds {

    public static final String BATCH5_THREE_ARM = "batch5-three-arm";
    public static final String BATCH5B_THREE_ARM = "batch5b-three-arm";

    private EvaluationIds() {}
}
