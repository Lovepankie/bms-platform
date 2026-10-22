package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * A device's report of a refused offline sale is at most {@link OfflineSalesService#MAX_REQUEST_BYTES} as a whole body
 * (ADR-067 decision 8): a larger one is 413 {@code offline_failure_too_large} before it is read into memory or parsed, a
 * declared length at once and a chunked body after that many bytes.
 */
@Component
class OfflineFailureBodyCap extends OncePerRequestFilter {

    static final String PATH = "/api/v1/retail/offline-failures";

    private final ObjectMapper mapper;

    OfflineFailureBodyCap(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int max = OfflineSalesService.MAX_REQUEST_BYTES;
        if (request.getContentLengthLong() > max) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new Buffered(request, body), response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        Problems.write(
                response,
                mapper,
                Problems.of(
                        HttpStatus.CONTENT_TOO_LARGE,
                        "offline_failure_too_large",
                        "Request too large",
                        "The sale request is larger than 64 KB."));
    }

    /** The body already read, served again to the controller. */
    private static final class Buffered extends HttpServletRequestWrapper {

        private final byte[] body;

        Buffered(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
