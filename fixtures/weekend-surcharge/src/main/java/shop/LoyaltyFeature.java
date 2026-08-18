package shop;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "shop.loyalty.enabled", havingValue = "true")
public class LoyaltyFeature {
}
