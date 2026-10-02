package com.zfy.zcinema.client.playback;

import com.zfy.zcinema.ZCinema;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers whether a source honours HTTP Range requests. This decides how seeking works: with
 * Range we can jump anywhere in the stream, without it every rewind has to reopen the stream and
 * decode forward - and, crucially, we must not keep calling setTimestamp on it, because that
 * corrupts the connection.
 */
final class RangeSupport {
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private RangeSupport() {}

    static boolean supports(String url) {
        return CACHE.computeIfAbsent(url, key -> {
            boolean supported = probe(key);
            if (!supported) warnOnce(key);
            return supported;
        });
    }

    static void forget(String url) {
        CACHE.remove(url);
    }

    private static boolean probe(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (Z Cinema)")
                    .header("Range", "bytes=0-0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                // 206 Partial Content means the source really honours Range requests.
                return response.statusCode() == 206;
            }
        } catch (Exception error) {
            return false;
        }
    }

    /** Warns once, the first time a source turns out to have no Range support. */
    static void warnOnce(String url) {
        ZCinema.LOGGER.warn("Source {} does not support HTTP Range requests: rewinding re-decodes from "
                + "the start, which can take seconds. Serve the file with nginx, caddy, "
                + "`npx http-server -c-1` or `python -m RangeHTTPServer 8000` to make seeking "
                + "instant.", url);
    }
}
