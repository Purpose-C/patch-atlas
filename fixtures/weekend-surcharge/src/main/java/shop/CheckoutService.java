package shop;

import java.time.DayOfWeek;
import org.springframework.beans.factory.annotation.Autowired;

public class CheckoutService {

    @Autowired
    private BillingService billingService;

    private final WeekendSurchargePolicy weekendSurchargePolicy = new WeekendSurchargePolicy();

    public int checkout(DayOfWeek day, int subtotalCents) {
        int surcharge = weekendSurchargePolicy.surchargeCents(day, subtotalCents);
        billingService.charge(subtotalCents + surcharge);
        return subtotalCents + surcharge;
    }
}
