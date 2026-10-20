package com.soundsphere.filter;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import java.io.IOException;

@WebFilter("/api/*")
public class ApiFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        HttpServletResponse s = (HttpServletResponse) res;

        String origin = r.getHeader("Origin");
        if (origin != null && (origin.contains("localhost") || origin.contains("127.0.0.1"))) {
            s.setHeader("Access-Control-Allow-Origin", origin);
            s.setHeader("Access-Control-Allow-Credentials", "true");
            s.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD");
            s.setHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token, Authorization, X-Requested-With");
        }

        if ("OPTIONS".equalsIgnoreCase(r.getMethod())) {
            s.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        s.setHeader("X-Content-Type-Options", "nosniff");
        s.setHeader("X-Frame-Options", "DENY");
        s.setHeader("Referrer-Policy", "same-origin");

        String uri = r.getRequestURI();
        boolean publicAuth = uri.contains("/api/auth/login") || uri.contains("/api/auth/register");
        if (!publicAuth && !"GET".equalsIgnoreCase(r.getMethod()) && !"HEAD".equalsIgnoreCase(r.getMethod()) && !com.soundsphere.util.Security.csrf(r, s)) {
            return;
        }
        chain.doFilter(req, res);
    }
}
