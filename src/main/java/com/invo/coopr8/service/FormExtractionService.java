package com.invo.coopr8.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.invo.coopr8.dto.FormScanResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * Reads a hardcopy cooperative membership form (scanned or photographed) with
 * Google Gemini vision, returning the member fields keyed by the frontend's
 * column names plus the Cloudinary URL of the passport photo cropped out of the
 * form.
 *
 * The Gemini key is read from {@code gemini.api-key}; when it is blank the scan
 * endpoint fails with a clear "not configured" message instead of the app
 * failing to boot. Calls go straight to the Generative Language REST API over
 * HttpURLConnection, so no vendor SDK is on the classpath.
 */
@Service
@RequiredArgsConstructor
public class FormExtractionService {

    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final CloudinaryService cloudinaryService;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-flash-lite-latest}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String PROMPT = """
            You are reading a scanned or photographed hardcopy cooperative-society \
            membership form. Extract the applicant's details and return ONLY a JSON \
            object (no markdown, no code fences, no commentary) with exactly these keys. \
            Use an empty string "" for any field that is absent or not legible. Never \
            invent values.

            {
              "firstName": "given name",
              "middleName": "middle name or empty",
              "lastName": "surname",
              "gender": "male or female, lowercase",
              "marital": "single/married/divorced/widowed lowercase, or empty",
              "email": "email address",
              "phone": "phone number",
              "address": "residential address",
              "psn": "payroll or staff number (salary-deduction / TESCOM staff)",
              "occupation": "occupation (self-paying members)",
              "verNo": "verification number if present",
              "station": "work station or place of posting",
              "homeTown": "home town",
              "lga": "local government area",
              "state": "state of origin",
              "paymentType": "GOVERNMENT if salary-deduction / TESCOM staff, else SELF_PAY",
              "savingPlan": "monthly savings amount, digits only e.g. 5000",
              "specialSavingPlan": "special savings amount digits only, or empty",
              "sharePlan": "share amount digits only, or empty",
              "nextOfKin": "next of kin full name",
              "nextOfKinRelationship": "relationship to the next of kin",
              "nextOfKinAddress": "next of kin address",
              "nextOfKinPhone": "next of kin phone",
              "passportPresent": true or false,
              "passportBox": { "x": 0.0, "y": 0.0, "width": 0.0, "height": 0.0 }
            }

            passportBox is the passport photograph's bounding box as fractions of the \
            full image: x,y = top-left corner, width,height = size, each between 0 and 1. \
            If no passport photo is visible set passportPresent to false and every \
            passportBox value to 0. Return the JSON object and nothing else.""";

    /** Scans one form image and returns the extracted fields + cropped passport URL. */
    public FormScanResponse scan(MultipartFile file) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Form scanning is not configured: set GEMINI_API_KEY in the environment.");
        }
        byte[] imageBytes = file.getBytes();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        String responseText = callGemini(base64, mimeTypeFor(file));

        FormExtraction extraction;
        try {
            extraction = objectMapper.readValue(stripToJson(responseText), FormExtraction.class);
        } catch (IOException e) {
            throw new IOException("Could not parse the form-scan response as JSON.", e);
        }

        String passportUrl = null;
        boolean passportDetected = false;
        String note = null;
        if (extraction.passportPresent && extraction.passportBox != null) {
            try {
                byte[] crop = cropPassport(imageBytes, extraction.passportBox);
                if (crop != null) {
                    passportUrl = cloudinaryService.uploadBytes(crop).get("url");
                    passportDetected = true;
                } else {
                    note = "A passport photo was detected but its location could not be cropped.";
                }
            } catch (Exception e) {
                note = "A passport photo was detected but could not be cropped/uploaded: "
                        + e.getMessage();
            }
        }

        return FormScanResponse.builder()
                .fields(toFields(extraction))
                .passport(passportUrl)
                .passportDetected(passportDetected)
                .note(note)
                .build();
    }

    /**
     * Calls the Gemini generateContent REST endpoint with the form image plus the
     * extraction prompt, asking for a JSON response, and returns the model's text.
     * responseMimeType=application/json makes Gemini emit bare JSON (no code fences).
     *
     * Free-tier keys throttle aggressively (a handful of requests per minute), so a
     * 429 in the middle of a batch is expected rather than exceptional. We retry it
     * a few times, honoring Google's suggested retry delay when present, so a form
     * isn't lost to a transient rate limit — the batch just paces itself.
     */
    private String callGemini(String base64Image, String mimeType) throws IOException {
        byte[] body = buildRequestBody(base64Image, mimeType);
        String urlStr = String.format(GEMINI_ENDPOINT, model);
        int maxAttempts = 4;

        for (int attempt = 1; ; attempt++) {
            // API key travels in the header, never in the URL/query (which can be logged).
            HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-goog-api-key", apiKey);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = stream == null
                    ? ""
                    : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            connection.disconnect();

            if (status >= 200 && status < 300) {
                return extractText(responseBody);
            }

            // 429 = rate-limited (free-tier quota), 503 = transiently overloaded.
            // Both are worth another try; anything else is a hard failure.
            boolean retryable = status == 429 || status == 503;
            if (retryable && attempt < maxAttempts) {
                sleepFor(retryDelayMillis(responseBody, attempt));
                continue;
            }
            throw new IOException("Gemini request failed (HTTP " + status + "): "
                    + extractError(responseBody));
        }
    }

    /** Builds the generateContent request body: the form image inline + the extraction prompt. */
    private byte[] buildRequestBody(String base64Image, String mimeType) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode parts = userTurn.putArray("parts");

        ObjectNode inlineDataPart = parts.addObject();
        ObjectNode inlineData = inlineDataPart.putObject("inline_data");
        inlineData.put("mime_type", mimeType);
        inlineData.put("data", base64Image);

        parts.addObject().put("text", PROMPT);

        ObjectNode genConfig = root.putObject("generationConfig");
        genConfig.put("temperature", 0);
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("maxOutputTokens", 8192);

        return objectMapper.writeValueAsBytes(root);
    }

    /**
     * How long to wait before retrying a throttled request. Prefers the RetryInfo
     * delay Google returns in the 429 body (e.g. "51s"); otherwise falls back to
     * exponential backoff. Clamped to 1–30s so one form can't stall the batch.
     */
    private long retryDelayMillis(String responseBody, int attempt) {
        try {
            JsonNode details = objectMapper.readTree(responseBody).path("error").path("details");
            if (details.isArray()) {
                for (JsonNode detail : details) {
                    String delay = detail.path("retryDelay").asText("");
                    if (!delay.isEmpty()) {
                        double seconds = Double.parseDouble(delay.replace("s", "").trim());
                        return Math.min(Math.max((long) (seconds * 1000), 1000L), 30000L);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to exponential backoff
        }
        return Math.min(2000L * (1L << (attempt - 1)), 30000L); // 2s, 4s, 8s, 16s...
    }

    /** Sleeps, surfacing an interrupt as an IOException so the scan fails cleanly. */
    private void sleepFor(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry the form scan.", e);
        }
    }

    /** Pulls candidates[0].content.parts[*].text out of a Gemini response. */
    private String extractText(String responseBody) throws IOException {
        JsonNode node = objectMapper.readTree(responseBody);
        JsonNode candidates = node.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            // A prompt blocked by safety filters comes back with promptFeedback and no candidates.
            String reason = node.path("promptFeedback").path("blockReason").asText("");
            throw new IOException("Gemini returned no candidates"
                    + (reason.isEmpty() ? "." : " (blocked: " + reason + ")."));
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                text.append(part.path("text").asText(""));
            }
        }
        if (text.length() == 0) {
            throw new IOException("Gemini returned an empty response for the form scan.");
        }
        return text.toString();
    }

    /** Best-effort extraction of the human-readable message from a Gemini error body. */
    private String extractError(String responseBody) {
        try {
            JsonNode message = objectMapper.readTree(responseBody).path("error").path("message");
            if (!message.isMissingNode() && !message.asText().isEmpty()) {
                return message.asText();
            }
        } catch (IOException ignored) {
            // fall through to the raw body
        }
        return responseBody.isEmpty() ? "no response body" : responseBody;
    }

    private String mimeTypeFor(MultipartFile file) {
        String type = file.getContentType();
        if (type != null) {
            String t = type.toLowerCase();
            if (t.contains("png")) return "image/png";
            if (t.contains("webp")) return "image/webp";
            if (t.contains("heic")) return "image/heic";
            if (t.contains("heif")) return "image/heif";
        }
        return "image/jpeg";
    }

    /**
     * Gemini is asked for bare JSON; defensively strip any stray prose or code
     * fences by taking the outermost {...} span.
     */
    private String stripToJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * Crops the passport out of the original image using the normalized box, with a
     * little padding so a tight box doesn't shave the photo. Returns JPEG bytes, or
     * null if the box is degenerate. Converts to RGB before encoding so a source
     * with alpha doesn't corrupt the JPEG.
     */
    private byte[] cropPassport(byte[] imageBytes, PassportBox box) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();

        double pad = 0.04;
        double x0 = clamp(box.x - pad, 0, 1);
        double y0 = clamp(box.y - pad, 0, 1);
        double x1 = clamp(box.x + box.width + pad, 0, 1);
        double y1 = clamp(box.y + box.height + pad, 0, 1);

        int px = (int) Math.round(x0 * w);
        int py = (int) Math.round(y0 * h);
        int pw = (int) Math.round((x1 - x0) * w);
        int ph = (int) Math.round((y1 - y0) * h);

        // Guard against a box that rounds to nothing or spills past the edge.
        px = Math.min(Math.max(px, 0), Math.max(w - 1, 0));
        py = Math.min(Math.max(py, 0), Math.max(h - 1, 0));
        pw = Math.min(pw, w - px);
        ph = Math.min(ph, h - py);
        if (pw < 8 || ph < 8) {
            return null;
        }

        BufferedImage crop = image.getSubimage(px, py, pw, ph);
        BufferedImage rgb = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(crop, 0, 0, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", out);
        return out.toByteArray();
    }

    private double clamp(double v, double min, double max) {
        return Math.min(Math.max(v, min), max);
    }

    /** Flattens the extraction into the frontend's canonical column map, dropping blanks. */
    private Map<String, String> toFields(FormExtraction e) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "firstName", e.firstName);
        put(f, "middleName", e.middleName);
        put(f, "lastName", e.lastName);
        put(f, "gender", e.gender);
        put(f, "marital", e.marital);
        put(f, "email", e.email);
        put(f, "phone", e.phone);
        put(f, "address", e.address);
        put(f, "psn", e.psn);
        put(f, "occupation", e.occupation);
        put(f, "verNo", e.verNo);
        put(f, "station", e.station);
        put(f, "homeTown", e.homeTown);
        put(f, "lga", e.lga);
        put(f, "state", e.state);
        put(f, "paymentType", e.paymentType);
        put(f, "savingPlan", e.savingPlan);
        put(f, "specialSavingPlan", e.specialSavingPlan);
        put(f, "sharePlan", e.sharePlan);
        put(f, "nextOfKin", e.nextOfKin);
        put(f, "nextOfKinRelationship", e.nextOfKinRelationship);
        put(f, "nextOfKinAddress", e.nextOfKinAddress);
        put(f, "nextOfKinPhone", e.nextOfKinPhone);
        return f;
    }

    private void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    /** The JSON shape Gemini is asked to return. Unknown properties are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class FormExtraction {
        public String firstName;
        public String middleName;
        public String lastName;
        public String gender;
        public String marital;
        public String email;
        public String phone;
        public String address;
        public String psn;
        public String occupation;
        public String verNo;
        public String station;
        public String homeTown;
        public String lga;
        public String state;
        public String paymentType;
        public String savingPlan;
        public String specialSavingPlan;
        public String sharePlan;
        public String nextOfKin;
        public String nextOfKinRelationship;
        public String nextOfKinAddress;
        public String nextOfKinPhone;
        public boolean passportPresent;
        public PassportBox passportBox;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PassportBox {
        public double x;
        public double y;
        public double width;
        public double height;
    }
}
