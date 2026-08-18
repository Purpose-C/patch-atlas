package shop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

public class NotificationRelay {

    @Autowired
    @Qualifier("mail")
    private Notifier notifier;

    public void notifyPaid(String orderId) {
        notifier.send(orderId);
    }
}
