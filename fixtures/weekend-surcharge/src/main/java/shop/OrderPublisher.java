package shop;

import org.springframework.context.ApplicationEventPublisher;

public class OrderPublisher {

    private ApplicationEventPublisher publisher;

    public void announce(String orderId) {
        publisher.publishEvent(new OrderPaid(orderId));
    }
}
