package io.github.patchatlas.run;

/**
 * 已记录 usage 与 Generation Attempt 的覆盖关系。
 *
 * <p>只描述系统记录了多少次供应商 usage，不证明账单完整。
 */
public enum RecordedUsageStatus {
    TRACKING_UNAVAILABLE,
    NONE_RECORDED,
    PARTIALLY_RECORDED,
    RECORDED_FOR_ALL_ATTEMPTS;

    public static RecordedUsageStatus from(Integer usageRecordCount, int attemptCount) {
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attempt count must not be negative");
        }
        if (usageRecordCount == null) {
            return TRACKING_UNAVAILABLE;
        }
        if (usageRecordCount < 0) {
            throw new IllegalArgumentException("usage record count must not be negative");
        }
        if (usageRecordCount > attemptCount) {
            throw new IllegalArgumentException(
                    "usage record count must not exceed generation attempt count");
        }
        if (usageRecordCount == 0) {
            return NONE_RECORDED;
        }
        if (usageRecordCount < attemptCount) {
            return PARTIALLY_RECORDED;
        }
        return RECORDED_FOR_ALL_ATTEMPTS;
    }
}
