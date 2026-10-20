package com.soundsphere.util;

import jakarta.servlet.http.*;
import java.io.IOException;

public final class Security {
    private Security() {}

    public static long uid(HttpServletRequest r) {
        Object v = r.getSession(false) == null ? null : r.getSession(false).getAttribute("uid");
        return v instanceof Long ? (Long) v : v instanceof Number ? ((Number) v).longValue() : 0;
    }

    public static String role(HttpServletRequest r) {
        Object v = r.getSession(false) == null ? null : r.getSession(false).getAttribute("role");
        return v == null ? "" : v.toString();
    }

    public static boolean requireUser(HttpServletRequest r, HttpServletResponse s) throws IOException {
        if (uid(r) == 0) {
            s.sendError(401, "Please sign in");
            return false;
        }
        return true;
    }

    public static boolean requireAdmin(HttpServletRequest r, HttpServletResponse s) throws IOException {
        if (!requireUser(r, s)) return false;
        if (!"ADMIN".equals(role(r))) {
            s.sendError(403, "Admin access required");
            return false;
        }
        return true;
    }

    public static boolean csrf(HttpServletRequest r, HttpServletResponse s) throws IOException {
        String method = r.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        HttpSession session = r.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute("csrf");
        String actual = r.getHeader("X-CSRF-Token");
        if (expected == null || !expected.equals(actual)) {
            s.sendError(403, "Invalid CSRF token");
            return false;
        }
        return true;
    }
}
