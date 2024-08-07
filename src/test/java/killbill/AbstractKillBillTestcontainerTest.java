package killbill;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.client.KillBillClientException;
import org.killbill.billing.client.KillBillHttpClient;
import org.killbill.billing.client.RequestOptions;
import org.killbill.billing.client.api.gen.AccountApi;
import org.killbill.billing.client.api.gen.SubscriptionApi;
import org.killbill.billing.client.model.gen.Account;
import org.killbill.billing.client.model.gen.Subscription;
import org.killbill.billing.util.tag.ControlTagType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public abstract class AbstractKillBillTestcontainerTest {

    protected static final RequestOptions REQUEST_OPTIONS = RequestOptions.builder().withCreatedBy("junit").build();

    private static final DockerImageName KILLBILL_IMAGE   = DockerImageName.parse("killbill/killbill:0.24.10");
    private static final DockerImageName KILLBILLDB_IMAGE = DockerImageName.parse("killbill/mariadb:0.24");

    private static Map<String, String> kbEnv = Map.of("KILLBILL_DAO_USER",
                                                      "root",
                                                      "KILLBILL_DAO_PASSWORD",
                                                      "killbill",
                                                      "KILLBILL_SECURITY_SHIRO_RESOURCE_PATH",
                                                      "file:/var/lib/killbill/shiro.ini",
                                                      "KB_org_killbill_server_test_mode",
                                                      "true",
                                                      "KILLBILL_PAYMENT_RETRY_DAYS",
                                                      "3,3,3",
                                                      "LOGSTASH_ENABLED",
                                                      "false",
                                                      "JVM_JDWP_PORT",
                                                      "0.0.0.0:12345");

    @SuppressWarnings("rawtypes")
    static GenericContainer killBillContainer = //
        new GenericContainer<>(KILLBILL_IMAGE)//
                                              .withExposedPorts(8080, 12345)//
                                              .withEnv(kbEnv)//
                                              .withCopyFileToContainer(MountableFile.forClasspathResource("killbill.properties"),
                                                                       "/var/lib/killbill/killbill.properties")
                                              .withCopyFileToContainer(MountableFile.forClasspathResource("killbill-shiro.ini"),
                                                                       "/var/lib/killbill/shiro.ini")
                                              .waitingFor(Wait.forHttp("/index.html").forPort(8080));

    @SuppressWarnings("rawtypes")
    static GenericContainer             killBillTestDbContainer = new GenericContainer<>(KILLBILLDB_IMAGE)                                       //
                                                                                                          .withExposedPorts(3306)                //
                                                                                                          .waitingFor(Wait.forListeningPort())   //
                                                                                                          .withEnv("MYSQL_ROOT_PASSWORD",
                                                                                                                   "killbill");
    protected static KillBillHttpClient httpClient;
    protected static String             killBillApiUrl;

    protected static HttpClient client = HttpClient.newBuilder().authenticator(new Authenticator() {

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication("admin", "password".toCharArray());
        }
    }).build();

    @SuppressWarnings("resource")
    @BeforeAll
    static void beforeAll() throws IOException, InterruptedException {

        System.out.println("Creating Docker containers, this will take a few seconds...");

        Network network = Network.newNetwork();

        killBillTestDbContainer.withNetwork(network);
        killBillTestDbContainer.withNetworkAliases("killbilltestdb");
        killBillTestDbContainer.start();

        killBillContainer.dependsOn(killBillTestDbContainer);
        killBillContainer.withEnv("KILLBILL_DAO_URL",
                                  String.format("jdbc:mysql://%s:%s/killbill", "killbilltestdb", 3306));
        killBillContainer.withNetwork(network);
        killBillContainer.start();

        killBillApiUrl =
            String.format("http://%s:%s", killBillContainer.getHost(), killBillContainer.getFirstMappedPort());
        httpClient = new KillBillHttpClient(killBillApiUrl, "admin", "password", "bob", "lazar");
        // upload fr config

        System.out.println("Adding Tenant \"FR\"");

        var createTenantRequest = http("/1.0/kb/tenants")//
                                                         .header("Content-Type", "application/json")
                                                         .POST(HttpRequest.BodyPublishers.ofString("""
                                                             {"apiKey": "bob", "apiSecret": "lazar", "externalKey": "FR"}
                                                             """))
                                                         .build();

        HttpResponse<String> createTenantResponse = client.send(createTenantRequest, BodyHandlers.ofString());
        System.out.println(createTenantResponse.body());
        Assertions.assertEquals(201, createTenantResponse.statusCode());

        System.out.println("Uploading Overdue Config");
        var overdueConfigRequest =
            http("/1.0/kb/overdue/xml").header("Content-Type", "text/xml")
                                       .POST(HttpRequest.BodyPublishers.ofInputStream(() -> Thread.currentThread()
                                                                                                  .getContextClassLoader()
                                                                                                  .getResourceAsStream("overdue-config.xml")))
                                       .build();
        var overdueConfigResponse = client.send(overdueConfigRequest, BodyHandlers.ofString());
        Assertions.assertEquals(201, overdueConfigResponse.statusCode());

        System.out.println("Uploading catalog");
        var catalogRequest = http("/1.0/kb/catalog/xml") //
                                                        .header("Content-Type", "text/xml")//
                                                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> Thread.currentThread()
                                                                                                                   .getContextClassLoader()
                                                                                                                   .getResourceAsStream("catalog.xml")))
                                                        .build();
        var catalogResponse = client.send(catalogRequest, BodyHandlers.ofString());
        System.out.println(catalogResponse.body());
        Assertions.assertEquals(201, catalogResponse.statusCode());

        httpClient = new KillBillHttpClient(killBillApiUrl, "admin", "password", "bob", "lazar");
    }

    protected static HttpRequest.Builder http(String pathAndParameters) {
        try {
            Builder builder = HttpRequest.newBuilder(new URI(killBillApiUrl + pathAndParameters));
            var additionalHeaders = Map.of("X-Killbill-ApiKey",
                                           "bob",
                                           "X-Killbill-ApiSecret",
                                           "lazar",
                                           "X-Killbill-CreatedBy",
                                           "demo",
                                           "X-Killbill-Reason",
                                           "demo",
                                           "X-Killbill-Comment",
                                           "demo");
            additionalHeaders.forEach(builder::header);
            builder.header("Authorization", "Basic " + URLEncoder.encode("admin:password", StandardCharsets.UTF_8));
            return builder;
        }
        catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    protected Account createTestAccount() throws KillBillClientException {
        var account = new Account();
        account.setName("Peter Parker");
        account.setCurrency(Currency.EUR);
        account = new AccountApi(httpClient).createAccount(account, REQUEST_OPTIONS);
        new AccountApi(httpClient).createAccountTags(account.getAccountId(),
                                                     List.of(ControlTagType.OVERDUE_ENFORCEMENT_OFF.getId()),
                                                     REQUEST_OPTIONS);
        return account;
    }

    protected Subscription createTestSubscription(UUID accountId, String planName) throws KillBillClientException {

        Subscription subscription = new Subscription();
        subscription.setAccountId(accountId);
        subscription.setPlanName(planName);
        return new SubscriptionApi(httpClient).createSubscription(subscription,
                                                                  (DateTime) null,
                                                                  (DateTime) null,
                                                                  null,
                                                                  (Boolean) null,
                                                                  (Boolean) null,
                                                                  true,
                                                                  60L,
                                                                  null,
                                                                  REQUEST_OPTIONS);
    }

    protected void waitForKillBillToCatchUp() throws IOException, InterruptedException {
        var request = http("/1.0/kb/test/queues?timeoutSec=600").GET().build();
        client.send(request, BodyHandlers.ofString());
    }

    protected DateTime getCurrentDate() throws IOException, InterruptedException {
        var request = http("/1.0/kb/test/clock").GET().build();
        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readerForMapOf(String.class).readTree(response.body());
        return DateTime.parse(tree.get("currentUtcTime").asText());
    }

    protected void setKbClock(DateTime target) throws IOException, InterruptedException {
        var date = getCurrentDate();
        System.out.println(String.format("KB time before setting the clock: %s", date));
        if (target.isAfter(date)) {
            while (date.isBefore(target)) {
                var duration = new Duration(date, target);
                if (duration.getStandardDays() >= 1) {
                    date = date.plusDays(1);
                }
                else {
                    date = target;
                }
                System.out.println(String.format("Moving time forward to %s", date));
                var request = http("/1.0/kb/test/clock?requestedDate="
                                   + URLEncoder.encode(date.toString(), StandardCharsets.UTF_8)
                                   + "&timeoutSec=600").POST(BodyPublishers.noBody()).build();
                client.send(request, BodyHandlers.ofString());
                // wait for kill bill to catch up
                waitForKillBillToCatchUp();
            }
        }
        else {
            System.out.println(String.format("Moving time backwards to %s", target));
            var request = http("/1.0/kb/test/clock?requestedDate="
                               + URLEncoder.encode(target.toString(), StandardCharsets.UTF_8)
                               + "&timeoutSec=600").POST(BodyPublishers.noBody()).build();
            client.send(request, BodyHandlers.ofString());

            // wait for kill bill to catch up
            waitForKillBillToCatchUp();
        }
        date = getCurrentDate();
        System.out.println(String.format("KB time after jump: %s", date));
    }
}
