package shop;

import org.springframework.transaction.annotation.Transactional;

public class BillingServiceImpl implements BillingService {

    @Transactional
    @Override
    public void charge(int amountCents) {
        // persist the charge; the transactional proxy target is not statically known
    }
}
