package com.star.utils;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLEncoder;

/**
 * HTTP Range 文件写出工具。
 *
 * <p>用于视频/音频在线播放与下载断点续传：解析 {@code Range} 请求头，
 * 命中时返回 {@code 206 Partial Content} 并只写出请求的字节区间；
 * 未携带 Range 时按 {@code 200} 全量写出。</p>
 *
 * <p>同时设置 {@code Accept-Ranges: bytes}（浏览器据此判断资源可随机访问，
 * 否则 video 标签的进度条无法拖动）与 {@code ETag}，
 * 并支持 {@code If-Range} 做条件请求校验。</p>
 */
@Slf4j
public final class RangeFileWriter {

    private static final int BUFFER_SIZE = 8192;

    private RangeFileWriter() {
    }

    /**
     * 按 Range 语义写出文件。
     *
     * @param request   用于读取 Range / If-Range 请求头与 MIME 推断
     * @param response  响应对象
     * @param file      物理文件
     * @param fileName  展示用文件名（决定 Content-Type 与下载文件名）
     * @param inline    true=inline（在线预览） false=attachment（下载）
     * @param etag      ETag 值（内容寻址项目可直接用物理文件 MD5），可为 null
     */
    public static void write(HttpServletRequest request,
                             HttpServletResponse response,
                             java.io.File file,
                             String fileName,
                             boolean inline,
                             String etag) throws IOException {

        long fileLength = file.length();
        long lastModified = file.lastModified();

        // 声明支持字节范围请求：这是 video/audio 能拖动进度条的前提
        response.setHeader("Accept-Ranges", "bytes");
        if (etag != null && !etag.isEmpty()) {
            response.setHeader("ETag", etag);
        }
        response.setDateHeader("Last-Modified", lastModified);

        String contentType = request.getServletContext().getMimeType(fileName);
        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/octet-stream";
        }
        response.setContentType(contentType);
        response.setHeader("Content-Disposition",
                (inline ? "inline" : "attachment") + "; filename=\""
                        + URLEncoder.encode(fileName, "UTF-8") + "\"");

        String rangeHeader = request.getHeader("Range");
        long[] range = null;
        if (rangeHeader != null && !rangeHeader.trim().isEmpty()
                && ifRangeMatches(request, etag, lastModified)) {
            range = parseSingleRange(rangeHeader, fileLength);
        }

        // 区间不合法：按 RFC 7233 返回 416，并告知当前资源总长度
        if (range != null && range[0] < 0) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + fileLength);
            return;
        }

        if (range == null) {
            writeFull(response, file, fileLength);
            return;
        }

        writePartial(response, file, fileLength, range[0], range[1]);
    }

    /** 200 全量写出 */
    private static void writeFull(HttpServletResponse response, java.io.File file, long fileLength)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLengthLong(fileLength);
        try (InputStream in = new FileInputStream(file);
             OutputStream out = response.getOutputStream()) {
            copy(in, out, fileLength);
            out.flush();
        }
    }

    /** 206 区间写出：seek 到起始偏移，只推送请求的那一段 */
    private static void writePartial(HttpServletResponse response, java.io.File file,
                                     long fileLength, long start, long end) throws IOException {
        long contentLength = end - start + 1;
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        response.setContentLengthLong(contentLength);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read == -1) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /**
     * 解析单段 Range。
     *
     * @return null 表示无法识别的形式（多段 / 语法错误），调用方应按 200 全量返回；
     *         {@code {-1,-1}} 表示语法正确但区间越界，应返回 416；
     *         正常返回 {@code {start, end}}（闭区间，均已裁剪到文件范围内）
     */
    private static long[] parseSingleRange(String header, long fileLength) {
        String value = header.trim();
        if (!value.startsWith("bytes=")) {
            return null;
        }
        value = value.substring("bytes=".length()).trim();
        // 多段 Range（bytes=0-99,200-299）暂不支持，按全量返回，规范允许
        if (value.contains(",")) {
            return null;
        }

        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startPart = value.substring(0, dash).trim();
        String endPart = value.substring(dash + 1).trim();

        long start;
        long end;
        try {
            if (startPart.isEmpty()) {
                // bytes=-N：最后 N 字节
                if (endPart.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    return new long[]{-1, -1};
                }
                if (fileLength == 0) {
                    return new long[]{-1, -1};
                }
                start = Math.max(0, fileLength - suffix);
                end = fileLength - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? fileLength - 1 : Long.parseLong(endPart);
            }
        } catch (NumberFormatException e) {
            return null;
        }

        if (start < 0 || start > end || start >= fileLength) {
            return new long[]{-1, -1};
        }
        if (end >= fileLength) {
            end = fileLength - 1;
        }
        return new long[]{start, end};
    }

    /**
     * If-Range 校验：客户端带着上次的 ETag 或 Last-Modified 做断点续传时，
     * 只有资源未变化才继续返回区间，否则回退为 200 全量（避免拼出损坏文件）。
     */
    private static boolean ifRangeMatches(HttpServletRequest request, String etag, long lastModified) {
        String ifRange = request.getHeader("If-Range");
        if (ifRange == null || ifRange.trim().isEmpty()) {
            return true;
        }
        ifRange = ifRange.trim();
        if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) {
            return etag != null && ifRange.equals(etag);
        }
        try {
            return request.getDateHeader("If-Range") == lastModified;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, toRead);
            if (read == -1) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
