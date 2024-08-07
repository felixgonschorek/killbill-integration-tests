package killbill;

import java.io.IOException;
import java.math.BigDecimal;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.PhaseType;
import org.killbill.billing.client.KillBillClientException;
import org.killbill.billing.client.RequestOptions;
import org.killbill.billing.client.api.gen.AccountApi;
import org.killbill.billing.client.api.gen.SubscriptionApi;
import org.killbill.billing.client.model.gen.EventSubscription;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementActionPolicy;
import org.killbill.billing.entitlement.api.Entitlement.EntitlementState;
import org.killbill.billing.entitlement.api.SubscriptionEventType;
import org.killbill.billing.util.api.AuditLevel;

@Tag("slow")
class KillBillReversalTimeTest extends AbstractKillBillTestcontainerTest {

    @Test
    void test_subscription_reversal_time() throws KillBillClientException, IOException, InterruptedException {
        // Create the account on the specific date
        setKbClock(DateTime.parse("2023-07-24T19:17:00+02:00"));
        var testAccount = createTestAccount();
        var subscription = createTestSubscription(testAccount.getAccountId(), "standard-monthly");

        // go forward in time and check the entitlement state (which should have transitioned from TRIAL to EVERGREEN)
        setKbClock(DateTime.parse("2023-07-31T20:00+02:00"));
        subscription =
            new SubscriptionApi(httpClient).getSubscription(subscription.getSubscriptionId(), RequestOptions.empty());
        testAccount = new AccountApi(httpClient).getAccount(testAccount.getAccountId(), RequestOptions.empty());
        Assertions.assertEquals(PhaseType.EVERGREEN, subscription.getPhaseType());
        Assertions.assertEquals(EntitlementState.ACTIVE, subscription.getState());

        // again, go forward in time and cancel the subscription
        setKbClock(DateTime.parse("2023-09-02T14:24+02:00"));
        new SubscriptionApi(httpClient).cancelSubscriptionPlan(subscription.getSubscriptionId(),
                                                               (DateTime) null,
                                                               EntitlementActionPolicy.IMMEDIATE,
                                                               BillingActionPolicy.START_OF_TERM,
                                                               null,
                                                               REQUEST_OPTIONS);
        subscription =
            new SubscriptionApi(httpClient).getSubscription(subscription.getSubscriptionId(), RequestOptions.empty());
        testAccount = new AccountApi(httpClient).getAccount(testAccount.getAccountId(),
                                                            true,
                                                            true,
                                                            AuditLevel.MINIMAL,
                                                            RequestOptions.empty());

        // debug output
        System.out.println(subscription);
        System.out.println(testAccount);
        System.out.println("Current time: " + getCurrentDate());
        DateTime effectiveEntitlementStop = subscription.getEvents().stream()//
                                                        .filter(e -> e.getEventType() == SubscriptionEventType.STOP_ENTITLEMENT)
                                                        .findFirst()//
                                                        .map(EventSubscription::getEffectiveDate)//
                                                        .orElseThrow();
        System.out.println("Effective entitlement stop: " + effectiveEntitlementStop);

        // we expect the account to have a positive account balance
        BigDecimal accountBalance = testAccount.getAccountBalance();
        BigDecimal expectedBalance = new BigDecimal("16.90");
        Assertions.assertTrue(accountBalance.compareTo(expectedBalance) == 0,
                              String.format("%s.compareTo(%s) == 0", accountBalance, expectedBalance));

        // and we expect the subscription to be cancelled
        Assertions.assertEquals(EntitlementState.CANCELLED, subscription.getState());
    }

}
