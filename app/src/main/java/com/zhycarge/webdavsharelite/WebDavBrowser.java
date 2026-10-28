package com.zhycarge.webdavsharelite;

import android.net.Uri;
import android.util.Xml;
import android.util.Base64;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tiny WebDAV directory client for API 16+.
 * HttpURLConnection refuses PROPFIND/MKCOL on many Android versions, so these
 * two requests use a short-lived socket, while PUT stays in WebDavClient.
 */
final class WebDavBrowser {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final String DAV_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>";

    static boolean validFolderName(String name) {
        return name != null && name.length() > 0 && name.length() <= 150 &&
                !name.equals(".") && !name.equals("..") &&
                name.indexOf('/') < 0 && name.indexOf('\\') < 0 &&
                name.indexOf('\r') < 0 && name.indexOf('\n') < 0;
    }

    static List<String> listDirectories(WebDavClient.Settings settings) throws Exception {
        settings.validate();
        String directoryUrl = WebDavClient.buildRemoteUrl(settings, "");
        Result response = request("PROPFIND", directoryUrl, settings,
                DAV_BODY.getBytes(UTF8), "1");
        if (response.code != 207 && response.code != 200) {
            throw failure(response, "读取目录");
        }
        LinkedHashSet<String> directories = new LinkedHashSet<String>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(response.body), "UTF-8");
        boolean inResponse = false, isDirectory = false;
        String href = null;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (isDav(parser, "response")) {
                    inResponse = true;
                    href = null;
                    isDirectory = false;
                } else if (inResponse && isDav(parser, "href") && href == null) {
                    href = parser.nextText();
                } else if (inResponse && isDav(parser, "collection")) {
                    isDirectory = true;
                }
            } else if (event == XmlPullParser.END_TAG && isDav(parser, "response")) {
                if (inResponse && isDirectory && href != null) {
                    String child = immediateChildName(directoryUrl, href);
                    if (child != null && validFolderName(child)) directories.add(child);
                }
                inResponse = false;
            }
        }
        List<String> result = new ArrayList<String>(directories);
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    static void makeDirectory(WebDavClient.Settings parent, String name) throws Exception {
        parent.validate();
        if (!validFolderName(name)) throw new IOException("不允许使用该文件夹名称");
        String url = WebDavClient.buildRemoteUrl(parent, name);
        Result response = request("MKCOL", url, parent, new byte[0], null);
        if (response.code != 201 && response.code != 200 && response.code != 204) {
            throw failure(response, "创建文件夹");
        }
    }

    private static boolean isDav(XmlPullParser p, String name) {
        return name.equals(p.getName()) && "DAV:".equals(p.getNamespace());
    }

    /** Return a single descendant folder's decoded name, skipping current and deeper levels. */
    private static String immediateChildName(String requestUrl, String href) {
        try {
            URL base = new URL(requestUrl);
            String parent = Uri.decode(base.getPath());
            if (!parent.endsWith("/")) parent += "/";
            String childPath = Uri.decode(new URL(base, href).getPath());
            if (!childPath.startsWith(parent)) return null;
            String relative = childPath.substring(parent.length());
            if (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
            if (relative.length() == 0 || relative.indexOf('/') >= 0) return null;
            return relative;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IOException failure(Result response, String operation) {
        String detail = new String(response.body, UTF8).replaceAll("[\\r\\n]+", " ");
        if (detail.length() > 180) detail = detail.substring(0, 180) + "...";
        if (response.code == 401) return new IOException("HTTP 401：账号、密码或 WebDAV 权限错误");
        if (response.code == 403) return new IOException("HTTP 403：没有列目录或新建文件夹的权限");
        if (response.code == 404) return new IOException("HTTP 404：WebDAV 地址或目录不存在");
        if (response.code == 405) return new IOException("HTTP 405：该目录已存在，或服务端不允许创建文件夹");
        if (response.code == 409) return new IOException("HTTP 409：父目录不存在或目录冲突");
        if (response.code >= 300 && response.code < 400)
            return new IOException("HTTP " + response.code + "：请直接填写最终的 HTTPS /dav/ 地址");
        return new IOException(operation + "失败：HTTP " + response.code + " " + detail);
    }

    private static final class Result {
        final int code;
        final byte[] body;
        Result(int code, byte[] body) { this.code = code; this.body = body; }
    }

    /** HTTP/1.1 request with Connection: close and bounded response body. */
    private static Result request(String method, String targetUrl, WebDavClient.Settings settings,
                                  byte[] body, String depth) throws IOException {
        URL url = new URL(targetUrl);
        String protocol = url.getProtocol().toLowerCase(java.util.Locale.US);
        if (!"http".equals(protocol) && !"https".equals(protocol)) throw new IOException("协议不支持");
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : ("https".equals(protocol) ? 443 : 80);
        Socket plain = new Socket();
        Socket connected = plain;
        try {
            plain.connect(new InetSocketAddress(host, port), 15000);
            plain.setSoTimeout(25000);
            if ("https".equals(protocol)) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, host, port, true);
                // Android 4.x sometimes supports TLS 1.2 but does not enable it by default.
                ArrayList<String> tlsVersions = new ArrayList<String>();
                for (String supported : tls.getSupportedProtocols()) {
                    if ("TLSv1.3".equals(supported) || "TLSv1.2".equals(supported))
                        tlsVersions.add(supported);
                }
                if (!tlsVersions.isEmpty())
                    tls.setEnabledProtocols(tlsVersions.toArray(new String[tlsVersions.size()]));
                tls.setSoTimeout(25000);
                tls.startHandshake();
                // Raw SSLSocket has no implicit hostname verification.
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession()))
                    throw new IOException("HTTPS 证书域名验证失败：" + host);
                connected = tls;
            }
            String requestPath = url.getFile();
            if (requestPath == null || requestPath.isEmpty()) requestPath = "/";
            if (requestPath.indexOf('\r') >= 0 || requestPath.indexOf('\n') >= 0)
                throw new IOException("URL 中含有非法换行");
            String hostHeader = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            if (url.getPort() > 0) hostHeader += ":" + port;
            String credentials = settings.username + ":" + settings.password;
            String authorization = Base64.encodeToString(credentials.getBytes(UTF8), Base64.NO_WRAP);
            StringBuilder headers = new StringBuilder();
            headers.append(method).append(' ').append(requestPath).append(" HTTP/1.1\r\n")
                   .append("Host: ").append(hostHeader).append("\r\n")
                   .append("Authorization: Basic ").append(authorization).append("\r\n")
                   .append("User-Agent: WebDAVShareLite/4.0\r\n")
                   .append("Connection: close\r\n")
                   .append("Accept-Encoding: identity\r\n")
                   .append("Content-Length: ").append(body.length).append("\r\n");
            if (depth != null) headers.append("Depth: ").append(depth).append("\r\n");
            if (body.length > 0) headers.append("Content-Type: application/xml; charset=utf-8\r\n");
            headers.append("\r\n");
            OutputStream out = connected.getOutputStream();
            out.write(headers.toString().getBytes(ASCII));
            if (body.length > 0) out.write(body);
            out.flush();

            BufferedInputStream in = new BufferedInputStream(connected.getInputStream());
            int code;
            int length;
            boolean chunked;
            do {
                String status = readLine(in);
                if (!status.startsWith("HTTP/")) throw new IOException("收到非 HTTP 响应：" + status);
                String[] parts = status.split(" ", 3);
                if (parts.length < 2) throw new IOException("HTTP 响应状态行格式错误");
                try { code = Integer.parseInt(parts[1]); }
                catch (NumberFormatException e) { throw new IOException("HTTP 状态码错误", e); }
                length = -1;
                chunked = false;
                String line;
                int headerBytes = 0;
                while (!(line = readLine(in)).isEmpty()) {
                    headerBytes += line.length();
                    if (headerBytes > 65536) throw new IOException("HTTP 响应头过大");
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.US);
                    String value = line.substring(colon + 1).trim();
                    if ("transfer-encoding".equals(key) &&
                            value.toLowerCase(java.util.Locale.US).contains("chunked")) chunked = true;
                    if ("content-length".equals(key)) {
                        try { length = Integer.parseInt(value); }
                        catch (NumberFormatException ignored) { length = -1; }
                    }
                }
            } while (code >= 100 && code < 200);
            return new Result(code, readBody(in, length, chunked));
        } finally {
            try { connected.close(); } catch (IOException ignored) { }
            if (connected != plain) try { plain.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readBody(InputStream in, int length, boolean chunked) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        if (chunked) {
            while (true) {
                String line = readLine(in).trim();
                int sep = line.indexOf(';');
                if (sep >= 0) line = line.substring(0, sep);
                int size;
                try { size = Integer.parseInt(line, 16); }
                catch (NumberFormatException e) { throw new IOException("无效的 HTTP chunk 大小", e); }
                if (size < 0 || size > MAX_BODY - out.size()) throw new IOException("目录列表超过 4MB 上限");
                if (size == 0) {
                    while (!readLine(in).isEmpty()) { /* trailer headers */ }
                    break;
                }
                readBytes(in, out, buffer, size);
                if (in.read() != '\r' || in.read() != '\n') throw new IOException("HTTP chunk 分隔符错误");
            }
        } else if (length >= 0) {
            if (length > MAX_BODY) throw new IOException("目录列表超过 4MB 上限");
            readBytes(in, out, buffer, length);
        } else {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_BODY) throw new IOException("目录列表超过 4MB 上限");
                out.write(buffer, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void readBytes(InputStream in, ByteArrayOutputStream out, byte[] buf, int size)
            throws IOException {
        while (size > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, size));
            if (n < 0) throw new IOException("HTTP 响应中途结束");
            if (n > 0) { out.write(buf, 0, n); size -= n; }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (out.size() >= 8192) throw new IOException("HTTP 响应行过长");
            if (c != '\r') out.write(c);
        }
        if (c == -1 && out.size() == 0) throw new IOException("服务器意外断开连接");
        return new String(out.toByteArray(), ASCII);
    }
}
