package shop;

import java.time.DayOfWeek;

/** Pricing rule for the weekend surcharge; Sunday is missing from the weekend check. */
public final class WeekendSurchargePolicy {

    /**
     * Weekend surcharge is 10% of the subtotal on Saturday and Sunday.
     */
    public int surchargeCents(DayOfWeek day, int subtotalCents) {
        if (day == DayOfWeek.SATURDAY) {
            return subtotalCents / 10;
        }
        return 0;
    }
}
