package com.bookflix.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bookflix.common.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/** Rejects request bodies larger than bookflix.security.max-request-bytes with 413. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final JsonErrorWriter errorWriter;

    public RequestSizeLimitFilter(BookflixProperties props, JsonErrorWriter errorWriter) {
        this.maxBytes = props.security().maxRequestBytes();
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            errorWriter.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE, "Request body too large");
            return;
        }
        chain.doFilter(declared < 0 ? new LimitedRequest(request, maxBytes) : request, response);
    }

    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("Request body too large");
        }
    }

    /** Guards chunked bodies without a Content-Length header. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream delegate = super.getInputStream();
                stream = new ServletInputStream() {
                    private long count;

                    @Override
                    public int read() throws IOException {
                        int b = delegate.read();
                        if (b != -1 && ++count > max) {
                            throw new PayloadTooLargeException();
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        int n = delegate.read(buf, off, len);
                        if (n > 0 && (count += n) > max) {
                            throw new PayloadTooLargeException();
                        }
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return delegate.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        delegate.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
