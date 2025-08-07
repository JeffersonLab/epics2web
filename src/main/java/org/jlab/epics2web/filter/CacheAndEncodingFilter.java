package org.jlab.epics2web.filter;

import java.io.IOException;
import java.util.Arrays;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * This WebFilter sets both the browser cache headers and request/response character set/encoding.
 *
 * This filter does not cause dynamically generated text/html to be cached - just static resources
 * like js, css, and images.
 *
 * Note: By setting the Expires header you now must bust the file from the cache every time you make
 * a change to the file. A good way to do this is by adding a version string into the file path or
 * as a URL parameter.
 *
 * @author slominskir
 */
@WebFilter(filterName = "CacheAndEncodingFilter", urlPatterns = {"/*"}, dispatcherTypes = {
    DispatcherType.REQUEST, DispatcherType.FORWARD})
public class CacheAndEncodingFilter implements Filter {

    public static final long EXPIRE_MILLIS = 31536000000L; // 365 days is max expires per spec

    public static final String[] CACHEABLE_CONTENT_TYPES = new String[]{
        "text/css", "text/javascript", "application/javascript", "image/png", "image/jpeg",
        "image/jpg",
        "image/gif", "image/icon", "image/x-icon", "image/vnd.microsoft.icon", "image/svg+xml"
    };

    static {
        Arrays.sort(CACHEABLE_CONTENT_TYPES);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");

        chain.doFilter(request, new CacheControlResponse(httpResponse));
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void destroy() {
    }

    class CacheControlResponse extends HttpServletResponseWrapper {

        public CacheControlResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            super.setContentType(type);

            if (type != null && Arrays.binarySearch(CACHEABLE_CONTENT_TYPES, type) > -1) {

                //System.out.println("cacheable: " + type);
                // We don't use Cache-Control max-age as it is just HTTP/1.1 replacement of HTTP/1.0 Expires, but Expires still works fine.
                super.setHeader("Cache-Control", null); // Remove header sometimes automatically added by SSL/TLS container module
                super.setHeader("Pragma", null); // Remove header sometimes automatically added by SSL/TLS container module              
                super.setDateHeader("Expires", System.currentTimeMillis() + EXPIRE_MILLIS);
            } else {

                //System.out.println("not cacheable: " + type);
                super.setHeader("Cache-Control", "no-store, no-cache, must-revalidate"); // HTTP 1.1
                super.setHeader("Pragma", "no-cache"); // HTTP 1.0
                super.setDateHeader("Expires", 0); // Proxies
            }
        }
    }
}
