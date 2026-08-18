package shop;

import org.springframework.context.event.EventListener;

public class OrderPaidListener {

    @EventListener
    public void onPaid(OrderPaid event) {
    }
}
