package dev.jota.gitlabcockpit.api

import java.nio.charset.StandardCharsets

/**
 * A `multipart/form-data` request body built by hand (GLC-56). The JDK [java.net.http.HttpClient]
 * ships no multipart publisher, so [buildFileMultipart] encodes the bytes itself and this holds the
 * two things the sender needs: [contentType] — the value to put on the request's `Content-Type`
 * header, carrying the boundary — and [body], the encoded bytes to publish.
 */
class MultipartBody(val contentType: String, val body: ByteArray)

/** The form field name GitLab's uploads endpoint expects the single file part to carry. */
private const val FILE_FIELD_NAME = "file"

/**
 * Builds a single-part `multipart/form-data` body carrying one file field named `file` (GLC-56, the
 * `POST /projects/:id/uploads` endpoint). Kept pure and free of any I/O so it can be unit-tested
 * against the exact on-the-wire bytes:
 *
 * ```
 * --<boundary>\r\n
 * Content-Disposition: form-data; name="file"; filename="<filename>"\r\n
 * Content-Type: <contentType>\r\n
 * \r\n
 * <bytes>\r\n
 * --<boundary>--\r\n
 * ```
 *
 * CRLF (`\r\n`) delimits every line, as the multipart grammar (RFC 2046 / 7578) requires, and the
 * body is closed with the `--<boundary>--` terminator. [filename] and [contentType] are written
 * verbatim: callers pass a real filesystem name and a resolved MIME type, neither of which carries a
 * CR/LF, so no header escaping is attempted here. The returned [MultipartBody.contentType] is the
 * `Content-Type` header value (`multipart/form-data; boundary=<boundary>`).
 */
fun buildFileMultipart(
    boundary: String,
    filename: String,
    contentType: String,
    bytes: ByteArray,
): MultipartBody {
    val header = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"").append(FILE_FIELD_NAME)
            .append("\"; filename=\"").append(filename).append("\"\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)
    val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)

    val body = ByteArray(header.size + bytes.size + footer.size)
    System.arraycopy(header, 0, body, 0, header.size)
    System.arraycopy(bytes, 0, body, header.size, bytes.size)
    System.arraycopy(footer, 0, body, header.size + bytes.size, footer.size)

    return MultipartBody("multipart/form-data; boundary=$boundary", body)
}
