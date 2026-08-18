package shop;

import org.springframework.beans.factory.annotation.Autowired;

public class AuditClient {

    @Autowired
    private AuditSink auditSink;

    public void audit(String event) {
        auditSink.record(event);
    }
}
