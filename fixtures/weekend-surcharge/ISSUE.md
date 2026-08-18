# Sunday checkout omits the weekend surcharge

Saturday checkouts add a 10% weekend surcharge to the subtotal.
Sunday checkouts are billed at the weekday rate, with no surcharge.

Expected: Saturday and Sunday both add 10% of the subtotal as a weekend surcharge.
Actual: only Saturday is treated as a weekend day.

The policy lives in the checkout pricing path (`WeekendSurchargePolicy`).
