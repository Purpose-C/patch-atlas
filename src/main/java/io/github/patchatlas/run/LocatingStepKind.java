package io.github.patchatlas.run;

/** locating_trace 允许写入的步骤类型。 */
public enum LocatingStepKind {
    SELECTION,
    EXCLUSION,
    SEARCH,
    LIST,
    READ,
    SUBMIT,
    BUDGET_EXHAUSTED,
    BUDGET_WARNING
}
