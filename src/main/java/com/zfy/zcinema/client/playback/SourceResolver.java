package com.zfy.zcinema.client.playback;

import com.zfy.zcinema.ZCinema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an indirect link into a direct one.
 *
 * <p>Plenty of sources hand out a page or an API URL instead of a file: Bilibili parsing
 * endpoints redirect straight to the CDN file, others answer with a JSON body that contains the
 * real address. FFmpeg can open the redirect target, but it cannot read a JSON document, so we
 * resolve the link once here and hand the session the actual stream URL.
 *
 * <p>Results are cached, because CDN addresses are signed and expire: after the cache lifetime
 * the link is resolved again, which is also what happens when a stream stops working.
 */
public final class SourceResolver {
    private static final long CACHE_MILLIS = 20 * 60 * 1000L;
    private static final int MAX_HOPS = 5;
    private static final int MAX_BODY_BYTES = 512 * 1024;
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final Pattern MEDIA_PATH = Pattern.compile(
            "\\.(mp4|flv|m3u8|mkv|mov|m4v|webm|mpd|ts|avi)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_URL = Pattern.compile(
            "https?://[^\\s\"'<>\\\\]+\\.(mp4|flv|m3u8|mkv|mov|m4v|webm|mpd)(\\?[^\\s\"'<>\\\\]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private record Resolved(String url, long resolvedAt) {}

    private SourceResolver() {}

    /** True when the URL already points at a file FFmpeg can open. */
    static boolean looksDirect(String url) {
        try {
            String path = URI.create(url).getPath();
            return path != null && MEDIA_PATH.matcher(path).find();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /** Resolves an indirect link to the stream URL behind it. */
    static String resolve(String url) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("empty url");
        if (looksDirect(url)) return url;
        Resolved cached = CACHE.get(url);
        if (cached != null && System.currentTimeMillis() - cached.resolvedAt() < CACHE_MILLIS) {
            return cached.url();
        }
        String resolved = follow(url);
        CACHE.put(url, new Resolved(resolved, System.currentTimeMillis()));
        ZCinema.LOGGER.info("Resolved {} to a direct stream", url);
        return resolved;
    }

    /** Called when a stream fails, so a stale resolution is not kept for its whole lifetime. */
    static void forget(String url) {
        CACHE.remove(url);
    }

    private static String follow(String startUrl) throws IOException {
        String current = startUrl;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(current))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", refererFor(current))
                        .GET()
                        .build();
            } catch (IllegalArgumentException error) {
                throw new IOException("malformed url " + current, error);
            }
            HttpResponse<InputStream> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while resolving " + current, error);
            }
            int status = response.statusCode();
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (status >= 200 && status < 300) {
                if (type.startsWith("video/") || type.startsWith("audio/")
                        || type.contains("octet-stream")) {
                    // It already points at media; we only needed the headers, so drop the body.
                    closeQuietly(response.body());
                    return current;
                }
                // Not media: the body may be JSON (or a page) that names the real address.
                // JSON payloads often escape the slashes of their URLs, pages escape ampersands.
                String body = readBody(response.body()).replace("\\/", "/").replace("&amp;", "&");
                Matcher matcher = MEDIA_URL.matcher(body);
                if (matcher.find()) {
                    current = matcher.group();
                    continue;
                }
                throw new IOException("the link answered " + (type.isBlank() ? "nothing playable" : type)
                        + " instead of a video");
            }
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                closeQuietly(response.body());
                if (location.isBlank()) throw new IOException("redirect without a target");
                try {
                    current = URI.create(current).resolve(location).toString();
                } catch (IllegalArgumentException error) {
                    throw new IOException("redirect to a broken address " + location, error);
                }
                continue;
            }
            closeQuietly(response.body());
            throw new IOException("HTTP " + status + " for " + current);
        }
        throw new IOException("too many redirects");
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    static String refererFor(String url) {
        // Bilibili's CDNs are picky about who asks for their files.
        return isBilibili(url) ? "https://www.bilibili.com/" : "https://" + hostOf(url) + "/";
    }

    private static boolean isBilibili(String url) {
        return url.contains("bilibili.com") || url.contains("bilivideo.com");
    }

    /**
     * The video and audio decoders open the resolved link themselves, so they have to knock on the
     * CDN's door with the same identity this resolver used: plenty of hosts answer FFmpeg's
     * default "Lavf/..." user agent with a 403, and Bilibili's CDNs additionally want the site as
     * referer - without this, a perfectly resolvable parsing API still ends in "failed to stream".
     */
    public static void applyStreamOptions(org.bytedeco.javacv.FFmpegFrameGrabber grabber, String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        grabber.setOption("user_agent", USER_AGENT);
        if (isBilibili(url)) {
            grabber.setOption("headers", "Referer: " + refererFor(url) + "\r\n");
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_BODY_BYTES) break;
                out.write(buffer, 0, read);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Readable reason for a failed resolve, for the control panel. */
    static String describe(Throwable error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("http 403") || message.contains("http 404")
                || message.contains("http 4")) {
            return "链接已失效（HTTP " + digits(message) + "），请重新获取";
        }
        if (message.contains("malformed url") || message.contains("redirect to a broken")) {
            return "链接格式不正确或该视频已下架";
        }
        if (message.contains("nothing playable") || message.contains("instead of a video")) {
            return "这个链接没有返回视频地址（解析接口可能失效）";
        }
        if (message.contains("too many redirects")) {
            return "链接跳转次数过多";
        }
        return "无法解析该链接：" + error.getMessage();
    }

    private static String digits(String message) {
        Matcher matcher = java.util.regex.Pattern.compile("\\d{3}").matcher(message);
        return matcher.find() ? matcher.group() : "4xx";
    }
}
