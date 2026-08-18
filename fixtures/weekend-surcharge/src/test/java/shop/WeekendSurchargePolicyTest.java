package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;

class WeekendSurchargePolicyTest {

    private final WeekendSurchargePolicy policy = new WeekendSurchargePolicy();

    @Test
    void saturdayAddsTenPercent() {
        assertEquals(10, policy.surchargeCents(DayOfWeek.SATURDAY, 100));
    }

    @Test
    void mondayAddsNothing() {
        assertEquals(0, policy.surchargeCents(DayOfWeek.MONDAY, 100));
    }
}
