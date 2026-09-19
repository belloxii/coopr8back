package com.invo.coopr8.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    private static final Pattern DRIVE_ID_PATTERN =
            Pattern.compile("(?:/file/d/|[?&]id=)([^/?&]+)");

    public Map<String, String> uploadImage(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new IOException("Image must be no larger than 5 MB.");
        }
        String type = file.getContentType();
        if (type == null || !(type.equalsIgnoreCase("image/jpeg") || type.equalsIgnoreCase("image/png")
                || type.equalsIgnoreCase("image/webp"))) {
            throw new IOException("Only JPEG, PNG, and WEBP images are accepted.");
        }
        byte[] imageBytes = file.getBytes();
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        if (decoded == null || decoded.getWidth() > 6000 || decoded.getHeight() > 6000) {
            throw new IOException("Image content or dimensions are not accepted.");
        }

        Map<?, ?> result = cloudinary.uploader().upload(
                imageBytes,
                ObjectUtils.asMap(
                        "folder", "passports"
                ));

        Map<String, String> response = new HashMap<>();

        response.put("url", result.get("secure_url").toString());
        response.put("publicId", result.get("public_id").toString());

        return response;
    }

    /**
     * Uploads raw image bytes (e.g. a passport cropped out of a scanned form)
     * to Cloudinary and returns its secure URL / public id.
     */
    public Map<String, String> uploadBytes(byte[] imageBytes) throws IOException {
        Map<?, ?> result = cloudinary.uploader().upload(
                imageBytes,
                ObjectUtils.asMap(
                        "folder", "passports"
                ));

        Map<String, String> response = new HashMap<>();
        response.put("url", result.get("secure_url").toString());
        response.put("publicId", result.get("public_id").toString());

        return response;
    }

    /**
     * Downloads an image from a remote URL and uploads it to Cloudinary.
     * Google Drive share links are converted to their direct-download form first.
     */
    public Map<String, String> uploadImageFromUrl(String imageUrl) throws IOException {
        byte[] imageBytes = downloadImageBytes(imageUrl);

        Map<?, ?> result = cloudinary.uploader().upload(
                imageBytes,
                ObjectUtils.asMap(
                        "folder", "passports"
                ));

        Map<String, String> response = new HashMap<>();
        response.put("url", result.get("secure_url").toString());
        response.put("publicId", result.get("public_id").toString());

        return response;
    }

    /**
     * Downloads raw image bytes from a URL. For Google Drive links the thumbnail
     * endpoint is tried first (it returns real image bytes and skips the
     * "confirm download" interstitial), falling back to the direct-download URL.
     * Verifies the response is actually an image, not an HTML error page.
     */
    private byte[] downloadImageBytes(String imageUrl) throws IOException {
        // This import exists solely for a controlled Google Drive migration. Fetching arbitrary
        // URLs turns an admin endpoint into SSRF against the app's private network.
        if (!isDriveLink(imageUrl)) {
            throw new IOException("Only Google Drive image links may be imported.");
        }
        Matcher matcher = DRIVE_ID_PATTERN.matcher(imageUrl);
        if (matcher.find()) {
            String fileId = matcher.group(1);
            try {
                return fetch("https://drive.google.com/thumbnail?id=" + fileId + "&sz=w1000");
            } catch (IOException thumbFailed) {
                return fetch("https://drive.google.com/uc?export=download&id=" + fileId);
            }
        }
        throw new IOException("Google Drive link does not contain a file id.");
    }

    /** Fetches a URL and returns its bytes, rejecting non-image (e.g. HTML) responses. */
    private byte[] fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        // Some hosts (Drive included) reject requests without a user agent.
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (InputStream in = connection.getInputStream()) {
            byte[] bytes = in.readNBytes(5 * 1024 * 1024 + 1);
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                throw new IOException("URL returned an HTML page, not an image "
                        + "(file may not be publicly shared): " + url);
            }
            if (bytes.length == 0) {
                throw new IOException("URL returned an empty response: " + url);
            }
            if (bytes.length > 5 * 1024 * 1024) {
                throw new IOException("Remote image exceeds the 5 MB import limit.");
            }
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    /** True when the value looks like a Google Drive share link. */
    public boolean isDriveLink(String url) {
        return url != null && url.contains("drive.google.com");
    }

    /**
     * Converts a Google Drive share link into a direct-download URL.
     * Non-Drive URLs are returned unchanged.
     */
    public String toDirectUrl(String url) {
        if (!isDriveLink(url)) {
            return url;
        }
        Matcher matcher = DRIVE_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return "https://drive.google.com/uc?export=download&id=" + matcher.group(1);
        }
        return url;
    }
}
