package in.apnidukan.shops.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The exact request Anthropic receives and how each answer is read, against a local fake (nothing is sent). */
class AnthropicLicenceReaderTest {

    private static final String KEY = "sk-ant-test-do-not-leak";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private final AtomicReference<String> versionHeader = new AtomicReference<>();
    private volatile int httpStatus = 200;
    private volatile String body = "";
    private volatile long delayMillis = 0;
    /** The retry-after header the fake answers with (a rate limit has one; the tier's spend cap doesn't). */
    private volatile String retryAfter = null;
    private HttpServer fake;
    /** The tokens the reader reported, per answer: {input, output}. */
    private final java.util.List<long[]> counted = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AnthropicLicenceReader reader() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/messages", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("retry-after", retryAfter);
            }
            exchange.sendResponseHeaders(httpStatus, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        fake.start();
        URI endpoint = URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/messages");
        return new AnthropicLicenceReader(KEY, "claude-sonnet-5-5", endpoint, Duration.ofSeconds(2),
                (in, out) -> counted.add(new long[] {in, out}));
    }

    @AfterEach
    void stop() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    /** An answer as structured outputs give it: thinking first, then the JSON as the text block's text. */
    static String answer(String input) {
        String text = JSON.createObjectNode().put("t", input.replace("\n", " ")).path("t").toString();
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","stop_reason":"end_turn",
                 "content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":%s}],
                 "usage":{"input_tokens":10,"output_tokens":10},"something_new":true}""".formatted(text);
    }

    @Test
    void sendsThePhotoWithTheJsonShapeItMustAnswerInAndReadsTheFacts() throws IOException {
        body = answer("""
                {"readable":true,"tradeLicence":true,"looksEdited":false,"provisional":false,"salonTrade":true,
                 "issuingBody":"Greater Hyderabad Municipal Corporation","licenceNumber":"TR-0934-422-0003",
                 "tradeName":"  Sharma Hair Studio ","ownerName":"Ravi","address":"Shop 4, Kondapur","pincode":"500084",
                 "tradeCategory":"Saloon","validUntil":"2027-03-31"}""");
        byte[] photo = {1, 2, 3, 4};
        LicenceExtract read = reader().read(photo, "image/jpeg");

        assertThat(read.readable()).isTrue();
        assertThat(read.tradeLicence()).isTrue();
        assertThat(read.salonTrade()).isTrue();
        assertThat(read.tradeName()).isEqualTo("Sharma Hair Studio");   // trimmed
        assertThat(read.licenceNumber()).isEqualTo("TR-0934-422-0003");
        assertThat(read.validUntil()).isEqualTo(LocalDate.of(2027, 3, 31));

        assertThat(keyHeader.get()).isEqualTo(KEY);
        assertThat(versionHeader.get()).isEqualTo("2023-06-01");
        JsonNode sent = JSON.readTree(request.get());
        assertThat(sent.path("model").asString()).isEqualTo("claude-sonnet-5-5");
        // Structured outputs, never forced tool use (Sonnet 5.5 refuses tool_choice "tool" with a 400) or temperature
        assertThat(sent.has("tool_choice")).isFalse();
        assertThat(sent.has("tools")).isFalse();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.path("output_config").path("effort").asString()).isEqualTo("low");
        JsonNode schema = sent.path("output_config").path("format").path("schema");
        assertThat(sent.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(schema.path("required").size()).isEqualTo(13);
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        JsonNode file = sent.path("messages").get(0).path("content").get(0);
        assertThat(file.path("type").asString()).isEqualTo("image");
        assertThat(file.path("source").path("media_type").asString()).isEqualTo("image/jpeg");
        assertThat(Base64.getDecoder().decode(file.path("source").path("data").asString())).isEqualTo(photo);
    }

    @Test
    void aPdfGoesAsADocumentAndMissingOrBadValuesAreNull() throws IOException {
        body = answer("""
                {"readable":true,"tradeLicence":false,"looksEdited":false,"provisional":false,"salonTrade":false,
                 "issuingBody":null,"licenceNumber":"","tradeName":null,"ownerName":null,"address":null,
                 "pincode":null,"tradeCategory":null,"validUntil":"31/03/2027"}""");
        LicenceExtract read = reader().read(new byte[] {37, 80, 68, 70}, "application/pdf");
        assertThat(read.tradeLicence()).isFalse();
        assertThat(read.licenceNumber()).isNull();
        assertThat(read.validUntil()).isNull();   // not YYYY-MM-DD
        JsonNode file = JSON.readTree(request.get()).path("messages").get(0).path("content").get(0);
        assertThat(file.path("type").asString()).isEqualTo("document");
        assertThat(file.path("source").path("media_type").asString()).isEqualTo("application/pdf");
    }

    @Test
    void busyOrFailingServiceIsTriedLaterAndARefusedFileOrAnAnswerNotInTheShapeIsUnusable() throws IOException {
        AnthropicLicenceReader reader = reader();
        java.util.Map<Integer, LicenceAiWatch.Problem> problems = new java.util.HashMap<>();
        problems.put(401, LicenceAiWatch.Problem.KEY);
        problems.put(403, LicenceAiWatch.Problem.KEY);
        problems.put(404, LicenceAiWatch.Problem.MODEL);   // the model's name, not the key
        problems.put(402, LicenceAiWatch.Problem.CREDIT);   // billing_error
        retryAfter = "30";   // a rate limit: just later
        for (int status : new int[] {429, 500, 529, 401, 403, 404, 402}) {
            httpStatus = status;
            body = "{\"type\":\"error\"}";
            assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                    .as("HTTP " + status).isInstanceOf(LicenceReader.ReaderUnavailableException.class)
                    .hasMessageNotContaining(KEY)
                    // the account's, ours to fix (the super admins are emailed); busy is just later
                    .extracting(e -> ((LicenceReader.ReaderUnavailableException) e).problem())
                    .isEqualTo(problems.get(status));
        }
        // The usage tier's monthly spend cap: a 429 without retry-after, failing until access resumes
        retryAfter = null;
        httpStatus = 429;
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.ReaderUnavailableException.class)
                .extracting(e -> ((LicenceReader.ReaderUnavailableException) e).problem())
                .isEqualTo(LicenceAiWatch.Problem.CREDIT);
        httpStatus = 400;
        body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"image exceeds 5 MB maximum\"}}";
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class)
                // the request was refused: nothing about whether the account works
                .extracting(e -> ((LicenceReader.UnusableAnswerException) e).answered()).isEqualTo(false);
        // Out of credit is the account's problem, not the file's: tried later, never the owner's fault
        body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit "
                + "balance is too low to access the Anthropic API.\"}}";
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.ReaderUnavailableException.class)
                .extracting(e -> ((LicenceReader.ReaderUnavailableException) e).problem())
                .isEqualTo(LicenceAiWatch.Problem.CREDIT);
        assertThat(counted).isEmpty();   // refused requests aren't billed
        httpStatus = 200;
        body = "{\"content\":[{\"type\":\"text\",\"text\":\"I can't help with that.\"}]}";
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class)
                .extracting(e -> ((LicenceReader.UnusableAnswerException) e).answered()).isEqualTo(true);
        body = answer("{\"readable\":true}").replace("end_turn", "refusal");   // may not match the shape
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class);
        body = answer("{\"readable\":true}").replace("end_turn", "max_tokens");   // cut off
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class);
        body = "{\"content\":[]}";
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class);
        body = "not json";
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class);
    }

    @Test
    void aSlowAnswerIsTriedLater() throws IOException {
        AnthropicLicenceReader reader = reader();
        delayMillis = 3_000;
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.ReaderUnavailableException.class);
    }

    @Test
    void onlyAnErrorAboutTheAccountIsTheAccountsProblem() {
        assertThat(AnthropicLicenceReader.accountProblem(
                "{\"error\":{\"message\":\"Your credit balance is too low\"}}")).isTrue();
        assertThat(AnthropicLicenceReader.accountProblem(
                "{\"error\":{\"message\":\"This feature isn't in your Plan\"}}")).isTrue();
        assertThat(AnthropicLicenceReader.accountProblem(
                "{\"error\":{\"message\":\"Could not process image: see explanation\"}}")).isFalse();
        assertThat(AnthropicLicenceReader.accountProblem("not json")).isFalse();
    }

    @Test
    void everyAnswerBilledReportsItsTokensUsableOrNot() throws IOException {
        AnthropicLicenceReader reader = reader();
        body = answer("{\"readable\":false}").replace("\"input_tokens\":10,\"output_tokens\":10",
                "\"input_tokens\":1800,\"cache_read_input_tokens\":5,\"output_tokens\":240");
        reader.read(new byte[] {1}, "image/jpeg");
        body = "{\"content\":[],\"usage\":{\"input_tokens\":900,\"output_tokens\":3}}";   // no facts: still billed
        assertThatThrownBy(() -> reader.read(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(LicenceReader.UnusableAnswerException.class);
        assertThat(counted).extracting(c -> c[0] + "/" + c[1]).containsExactly("1805/240", "900/3");
    }
}
