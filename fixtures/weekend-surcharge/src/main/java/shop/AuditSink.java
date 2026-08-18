package shop;

public interface AuditSink {
    void record(String event);
}
