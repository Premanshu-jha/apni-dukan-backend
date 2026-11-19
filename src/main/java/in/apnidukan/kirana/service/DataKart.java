package in.apnidukan.kirana.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static in.apnidukan.kirana.service.RazorpayHttp.JSON;

/**
 * GS1 India's DataKart, the national product registry: a product's official name, brand, net content and pictures
 * by its barcode (GTIN), and products found by name. Switched on only when {@code GS1_DATAKART_URL} and
 * {@code GS1_DATAKART_KEY} are set; off, nothing is ever called.
 *
 * <p>Written before access to DataKart's own API documentation: it asks {@code GET <url>/products/<gtin>} and
 * {@code GET <url>/products?q=<name>} with the key as a bearer token, and reads the answer under the field names GS1
 * uses for its core product attributes (gtin, brandName, productDescription or productName, netContent with its unit,
 * productImageUrl or images). When the real API differs, only this class changes.
 *
 * <p>Pictures are fetched only over https (or DataKart's own scheme, on its own host) from DataKart's own host or the
 * hosts in {@code GS1_IMAGE_HOSTS} (never an address an answer could point anywhere), at most 10 MB, no redirects.
 */
@Component
public final class DataKart {

    private static final Logger log = LoggerFactory.getLogger(DataKart.class);
    private static final long MAX_PICTURE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FOUND = 10;

    private final String url;
    private final String key;
    private final Set<String> pictureHosts;
    private final HttpClient http;

    public DataKart(@Value("${app.kirana.gs1.url:}") String url, @Value("${app.kirana.gs1.key:}") String key,
                    @Value("${app.kirana.gs1.image-hosts:}") String imageHosts) {
        this.url = setting(url).replaceAll("/+$", "");
        this.key = setting(key);
        Set<String> hosts = Arrays.stream(setting(imageHosts).split(","))
                .map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                .collect(Collectors.toCollection(java.util.HashSet::new));
        host(this.url).ifPresent(hosts::add);
        this.pictureHosts = Set.copyOf(hosts);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * A setting as given, or empty when it isn't really set: blank, or a placeholder (NULL, NONE, OFF, any case), since
     * Railway can't keep a variable without a value.
     */
    static String setting(String value) {
        String v = value == null ? "" : value.strip();
        return v.equalsIgnoreCase("null") || v.equalsIgnoreCase("none") || v.equalsIgnoreCase("off") ? "" : v;
    }

    /** Both settings have real values: the GS1 job runs. */
    public boolean configured() {
        return !url.isEmpty() && !key.isEmpty();
    }

    /**
     * A product as GS1 knows it.
     * @param netContent as printed ("500 ml"), or null
     * @param pictures   https links to its pictures, the front first
     */
    public record Gs1Product(String gtin, String brand, String name, String netContent, List<String> pictures) {

        public Gs1Product {
            pictures = List.copyOf(pictures);
        }
    }

    /** DataKart couldn't be reached or answered with an error: tried again later. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** DataKart refused the key (401 or 403): nothing is looked up until the settings are fixed. */
    public static class Refused extends Unavailable {
        public Refused(String message) {
            super(message);
        }
    }

    /** The product with this barcode; empty when GS1 doesn't know it. */
    public Optional<Gs1Product> byGtin(String gtin) {
        JsonNode in = get("/products/" + URLEncoder.encode(gtin, StandardCharsets.UTF_8));
        if (in == null) {
            return Optional.empty();
        }
        JsonNode p = in.has("product") ? in.path("product") : in.has("data") ? first(in.path("data")) : in;
        return Optional.ofNullable(product(p, gtin));
    }

    /** Products whose name looks like this (at most 10); empty when none, or DataKart can't search by name. */
    public List<Gs1Product> search(String name) {
        JsonNode in = get("/products?q=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (in == null) {
            return List.of();
        }
        JsonNode list = in.isArray() ? in : in.has("products") ? in.path("products") : in.path("data");
        List<Gs1Product> out = new ArrayList<>();
        for (JsonNode p : list) {
            Gs1Product g = product(p, null);
            if (g != null && g.gtin() != null) {
                out.add(g);
            }
            if (out.size() >= MAX_FOUND) {
                break;
            }
        }
        return out;
    }

    /** A picture's bytes and type; empty when its link isn't one DataKart may give, or it isn't there. */
    public Optional<byte[]> picture(String link) {
        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // https only (or DataKart's own scheme on its own host: a test's local DataKart), from the allowed hosts
        boolean ownHost = uri.getHost() != null && host(url).filter(uri.getHost()::equalsIgnoreCase).isPresent();
        boolean scheme = "https".equalsIgnoreCase(uri.getScheme())
                || ownHost && uri.getScheme() != null && url.toLowerCase(Locale.ROOT)
                .startsWith(uri.getScheme().toLowerCase(Locale.ROOT) + "://");
        if (!scheme || uri.getHost() == null || !pictureHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            log.warn("A GS1 picture link on a host not allowed was left out: {}", uri.getHost());
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
        try {
            HttpResponse<InputStream> res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                if (res.statusCode() >= 400 && res.statusCode() < 500) {
                    return Optional.empty();   // gone or not ours to fetch: the next picture is tried
                }
                if (res.statusCode() != 200) {
                    throw new Unavailable("GS1 picture answered " + res.statusCode());
                }
                byte[] bytes = body.readNBytes((int) MAX_PICTURE_BYTES + 1);
                return bytes.length > MAX_PICTURE_BYTES ? Optional.empty() : Optional.of(bytes);
            }
        } catch (IOException e) {
            throw new Unavailable("GS1 picture couldn't be fetched: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted");
        }
    }

    /** The answer to a GET, or null for 404 (not known). */
    private JsonNode get(String path) {
        if (!configured()) {
            throw new IllegalStateException("GS1 DataKart isn't set up");
        }
        HttpRequest request;
        try {
            URI uri = URI.create(url + path);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
                throw new Refused("GS1_DATAKART_URL isn't a web address (https://…)");
            }
            request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + key).header("Accept", "application/json").GET().build();
        } catch (IllegalArgumentException e) {
            throw new Refused("GS1_DATAKART_URL isn't a web address: " + e.getMessage());
        }
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                return null;
            }
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                throw new Refused("GS1 DataKart refused the key (" + res.statusCode() + "): check GS1_DATAKART_KEY");
            }
            if (res.statusCode() == 408 || res.statusCode() == 429) {
                throw new Unavailable("GS1 DataKart is busy (" + res.statusCode() + ")");
            }
            if (res.statusCode() >= 400 && res.statusCode() < 500 && path.startsWith("/products?")) {
                return null;   // it can't search like this (400, 405, 501…): no match by name, never a retry
            }
            if (res.statusCode() != 200) {
                throw new Unavailable("GS1 DataKart answered " + res.statusCode());
            }
            return JSON.readTree(res.body());
        } catch (JacksonException e) {
            throw new Unavailable("GS1 DataKart's answer couldn't be read");
        } catch (IOException e) {
            throw new Unavailable("GS1 DataKart couldn't be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted");
        }
    }

    /**
     * A GTIN as packs print it: GS1 often gives 14 digits (a leading 0 before an EAN-13's 13, six before an EAN-8's 8),
     * which a scan never reads.
     */
    static String gtin13(String code) {
        String digits = code.replaceAll("[^0-9]", "");
        while (digits.length() > 13 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        // An EAN-8 padded to 13 (00000 + 8 digits): packs print, and scans read, its 8
        return digits.length() == 13 && digits.startsWith("00000") ? digits.substring(5) : digits;
    }

    /** The link's host, lower case; empty when it has none or isn't a link. */
    private static Optional<String> host(String link) {
        try {
            return link.isEmpty() ? Optional.empty()
                    : Optional.ofNullable(URI.create(link).getHost()).map(h -> h.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static JsonNode first(JsonNode n) {
        return n.isArray() ? n.path(0) : n;
    }

    /** A product from the answer's fields (GS1's core attribute names, or close ones); null without a name. */
    private static Gs1Product product(JsonNode p, String gtin) {
        if (p == null || !p.isObject()) {
            return null;
        }
        String name = text(p, "productName", "productDescription", "name", "description", "functionalName");
        if (name == null) {
            return null;
        }
        String code = text(p, "gtin", "GTIN", "barcode");
        String content = text(p, "netContent", "net_content", "netWeight", "quantity");
        String unit = text(p, "netContentUom", "netContentUnit", "unitOfMeasure", "uom");
        if (content != null && unit != null && content.matches("[0-9.]+")) {
            content = content + " " + unit.toLowerCase(Locale.ROOT);
        }
        List<String> pictures = new ArrayList<>();
        for (String field : List.of("productImageUrl", "imageUrl", "image", "frontImage")) {
            String link = text(p, field);
            if (link != null) {
                pictures.add(link);
            }
        }
        for (JsonNode i : p.path("images")) {
            String link = i.isString() ? i.asString() : text(i, "url", "link", "imageUrl");
            if (link != null && !pictures.contains(link)) {
                pictures.add(link);
            }
        }
        return new Gs1Product(code == null ? gtin : gtin13(code),
                cut(text(p, "brandName", "brand"), 60), cut(name, 100), cut(content, 30), pictures);
    }

    private static String text(JsonNode n, String... fields) {
        for (String f : fields) {
            JsonNode v = n.path(f);
            String s = v.isString() ? v.asString() : v.isNumber() ? v.asString() : null;
            if (s != null && !s.isBlank()) {
                return s.strip();
            }
        }
        return null;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max).strip();
    }
}
