package shop;

public class OrderPaid {

    private final String orderId;

    public OrderPaid(String orderId) {
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}
