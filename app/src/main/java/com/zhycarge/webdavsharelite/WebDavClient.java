package com.zhycarge.webdavsharelite;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dependency-free WebDAV PUT client. HTTP requests run on the caller's worker thread.
 * All content:// URI data is accessed through ContentResolver (no storage permission).
 */
final class WebDavClient {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Settings {
        final String baseUrl;
        final String username;
        final String password;
        final String folder;
        final boolean addTimestamp;

        Settings(String baseUrl, String username, String password,
                 String folder, boolean addTimestamp) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.folder = folder == null ? "" : folder.trim();
            this.addTimestamp = addTimestamp;
        }

        void validate() throws IOException {
            if (baseUrl.isEmpty()) throw new IOException("请填写 WebDAV URL，例如 http://192.168.110.114:5244/dav/");
            URL url;
            try { url = new URL(baseUrl); }
            catch (Exception e) { throw new IOException("WebDAV URL 格式不正确", e); }
            String p = url.getProtocol();
            if (!"http".equalsIgnoreCase(p) && !"https".equalsIgnoreCase(p)) {
                throw new IOException("只支持 HTTP 或 HTTPS 协议");
            }
            if (url.getHost() == null || url.getHost().length() == 0) {
                throw new IOException("URL 缺少服务器主机名或 IP");
            }
            if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
                throw new IOException("URL 中不能包含账号、查询参数或 #片段");
            }
            if (username.trim().length() == 0) throw new IOException("请填写 OpenList 用户名");
            String[] segments = folder.replace('\\', '/').split("/", -1);
            for (String segment : segments) {
                if (segment.equals(".") || segment.equals(".."))
                    throw new IOException("上传目录不允许包含 . 或 ..");
            }
        }
    }

    static final class SourceFile {
        final Uri uri;
        final String name;
        final long length; // -1 if unknown
        final String mimeType;

        SourceFile(Uri uri, String name, long length, String mimeType) {
            this.uri = uri;
            this.name = name;
            this.length = length;
            this.mimeType = mimeType;
        }
    }

    static SourceFile inspect(Context context, Uri uri, int index) {
        String name = null;
        long size = -1;
        String mime = null;
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {
            // Some gallery providers do not support metadata queries. Fall back to URI.
        } finally {
            if (cursor != null) cursor.close();
        }
        try { mime = resolver.getType(uri); }
        catch (Exception ignored) { }
        if ((name == null || name.length() == 0) && "file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                File f = new File(path);
                name = f.getName();
                if (size < 0 && f.isFile()) size = f.length();
            }
        }
        if (name == null || name.length() == 0) {
            String extension = "bin";
            if (mime != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext != null && !ext.isEmpty()) extension = ext;
            }
            name = "shared_" + index + "." + extension;
        }
        return new SourceFile(uri, safeFileName(name), size, mime);
    }

    static String buildRemoteUrl(Settings settings, String filename) throws IOException {
        settings.validate();
        StringBuilder sb = new StringBuilder(settings.baseUrl);
        if (!settings.baseUrl.endsWith("/")) sb.append('/');
        String folder = settings.folder.replace('\\', '/');
        for (String part : folder.split("/", -1)) {
            if (part.isEmpty()) continue;
            sb.append(urlSegment(part)).append('/');
        }
        sb.append(urlSegment(filename));
        return sb.toString();
    }

    static String timestampedName(String name, int position) {
        String prefix = new SimpleDateFormat("yyyyMMdd_HHmmssSS", Locale.US).format(new Date());
        return prefix + "_" + position + "_" + name;
    }

    /** RFC3986 percent-encode one path segment (not URLEncoder's form-encoding). */
    private static String urlSegment(String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        StringBuilder out = new StringBuilder(bytes.length * 3);
        final char[] HEX = "0123456789ABCDEF".toCharArray();
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >>> 4]).append(HEX[c & 15]);
            }
        }
        return out.toString();
    }

    private static String safeFileName(String name) {
        name = name.replace('/', '_').replace('\\', '_');
        name = name.replace('\r', '_').replace('\n', '_').trim();
        if (name.equals(".") || name.equals("..") || name.isEmpty()) name = "shared_file.bin";
        // Avoid excessively long filename segments in common filesystems.
        if (name.length() > 150) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 12) ? name.substring(dot) : "";
            name = name.substring(0, Math.min(130, name.length())) + ext;
        }
        return name;
    }

    /**
     * Returns HTTP status if successful, otherwise throws an exception with the server response.
     */
    static int upload(Context context, Settings settings, SourceFile file, String remoteName)
            throws IOException {
        String remote = buildRemoteUrl(settings, remoteName);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(remote).openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false); // Never forward credentials across redirects.
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", basicHeader(settings.username, settings.password));
            conn.setRequestProperty("Content-Type", file.mimeType != null ? file.mimeType : "application/octet-stream");
            // Server-dependent collision guard; timestamp naming is enabled by default.
            conn.setRequestProperty("If-None-Match", "*");
            // The gallery's OpenableColumns.SIZE can differ from the bytes
            // actually returned by openInputStream() (e.g. transcoded photos).
            // Never use that metadata as an exact HTTP Content-Length.
            // Chunked PUT streams the real bytes without buffering in RAM.
            conn.setChunkedStreamingMode(16 * 1024);

            ContentResolver resolver = context.getContentResolver();
            InputStream raw = resolver.openInputStream(file.uri);
            if (raw == null) throw new IOException("相册没有提供可读取的文件流：" + file.name);
            InputStream input = new BufferedInputStream(raw);
            OutputStream output = null;
            try {
                output = conn.getOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("上传已取消");
                    if (len > 0) output.write(buffer, 0, len);
                }
                output.flush();
            } finally {
                // Explicit finally blocks also work on older Android runtimes (API 16).
                try { input.close(); } catch (IOException ignored) { }
                if (output != null) try { output.close(); } catch (IOException ignored) { }
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return code;
            String response = limitedResponse(conn.getErrorStream());
            if (code >= 300 && code < 400) {
                throw new IOException("HTTP " + code + " 重定向：请直接配置最终的 WebDAV HTTPS 地址；" + response);
            }
            if (code == 401) throw new IOException("HTTP 401：用户名/密码错误，或账号没有 WebDAV 权限。" + response);
            if (code == 403) throw new IOException("HTTP 403：OpenList 没有允许该用户上传，或文件夹没有写权限。" + response);
            if (code == 404) throw new IOException("HTTP 404：WebDAV 地址或上传目录不存在。" + response);
            if (code == 409) throw new IOException("HTTP 409：目标文件夹不存在或路径冲突，请先在 OpenList 创建该目录。" + response);
            if (code == 412) throw new IOException("HTTP 412：同名文件已存在，服务器拒绝覆盖。" + response);
            if (code == 413) throw new IOException("HTTP 413：文件超过服务器上传限制。" + response);
            throw new IOException("WebDAV 上传失败，HTTP " + code + " " + response);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String basicHeader(String username, String password) {
        byte[] bytes = (username + ":" + password).getBytes(UTF8);
        return "Basic " + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static String limitedResponse(InputStream stream) {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[512];
            int n;
            while (out.size() < 1400 && (n = stream.read(buf)) != -1) {
                out.write(buf, 0, Math.min(n, 1400 - out.size()));
            }
            return new String(out.toByteArray(), UTF8).replaceAll("[\\r\\n]+", " ");
        } catch (Exception e) {
            return "（无法读取服务器错误正文）";
        } finally {
            try { stream.close(); } catch (IOException ignored) { }
        }
    }
}
