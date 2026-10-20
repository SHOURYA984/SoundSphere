package com.soundsphere.servlet;

import com.soundsphere.config.Db;
import com.soundsphere.util.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import org.mindrot.jbcrypt.BCrypt;

@WebServlet("/api/auth/*")
public class AuthServlet extends HttpServlet {
    protected void doGet(HttpServletRequest r, HttpServletResponse s) throws IOException {
        if ("/me".equals(r.getPathInfo())) {
            long id = Security.uid(r);
            if (id == 0) {
                Json.send(s, 200, Json.obj("authenticated", false));
                return;
            }
            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("SELECT id, name, email, role FROM users WHERE id=? AND status='ACTIVE'")) {
                p.setLong(1, id);
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) {
                        Object csrfToken = r.getSession() != null ? r.getSession().getAttribute("csrf") : null;
                        Json.send(s, 200, Json.obj(
                            "authenticated", true,
                            "id", rs.getLong(1),
                            "name", rs.getString(2),
                            "email", rs.getString(3),
                            "role", rs.getString(4),
                            "csrf", csrfToken
                        ));
                    } else {
                        Json.send(s, 200, Json.obj("authenticated", false));
                    }
                }
            } catch (Exception e) {
                getServletContext().log("Me endpoint error", e);
                Json.send(s, 500, Json.obj("error", "Database error"));
            }
        } else {
            s.sendError(404);
        }
    }

    protected void doPost(HttpServletRequest r, HttpServletResponse s) throws IOException {
        String path = r.getPathInfo();
        Map<String, Object> b = Json.body(r);
        if ("/register".equals(path)) {
            String name = Json.str(b, "name");
            String email = Json.str(b, "email").toLowerCase(Locale.ROOT);
            String pw = Json.str(b, "password");

            // Valid Java regex string escaping for email check
            if (name.length() < 2 || name.length() > 120 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || pw.length() < 10) {
                Json.send(s, 400, Json.obj("error", "Enter a valid name/email and a password of at least 10 characters"));
                return;
            }

            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("INSERT INTO users(name, email, password_hash, role) VALUES(?,?,?,'MUSICIAN')", Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1, name);
                p.setString(2, email);
                p.setString(3, BCrypt.hashpw(pw, BCrypt.gensalt(12)));
                p.executeUpdate();

                try (ResultSet keys = p.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    try (PreparedStatement pr = c.prepareStatement("INSERT INTO user_profiles(user_id) VALUES(?)")) {
                        pr.setLong(1, id);
                        pr.executeUpdate();
                    }

                    HttpSession session = r.getSession(false);
                    if (session != null) {
                        r.changeSessionId();
                    } else {
                        session = r.getSession(true);
                    }
                    session.setAttribute("uid", id);
                    session.setAttribute("role", "MUSICIAN");
                    session.setAttribute("name", name);
                    session.setMaxInactiveInterval(1800);
                    String csrfToken = UUID.randomUUID().toString();
                    session.setAttribute("csrf", csrfToken);

                    log(c, id, "REGISTER", "Account created for " + email);

                    Json.send(s, 201, Json.obj(
                        "message", "Account created",
                        "role", "MUSICIAN",
                        "csrf", csrfToken
                    ));
                }
            } catch (SQLIntegrityConstraintViolationException e) {
                Json.send(s, 409, Json.obj("error", "An account with this email already exists"));
            } catch (Exception e) {
                getServletContext().log("Registration failed", e);
                Json.send(s, 500, Json.obj("error", "Registration failed: " + (e.getMessage() != null ? e.getMessage() : "check database configuration")));
            }
        } else if ("/login".equals(path)) {
            String email = Json.str(b, "email").toLowerCase(Locale.ROOT);
            String pw = Json.str(b, "password");

            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("SELECT id, name, role, password_hash, status FROM users WHERE email=?")) {
                p.setString(1, email);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next() || !BCrypt.checkpw(pw, rs.getString(4))) {
                        log(c, 0, "LOGIN_FAILED", "Failed login for " + email);
                        Json.send(s, 401, Json.obj("error", "Email or password is incorrect"));
                        return;
                    }

                    if (!"ACTIVE".equals(rs.getString(5))) {
                        Json.send(s, 403, Json.obj("error", "Your account has been disabled. Please contact an administrator"));
                        return;
                    }

                    HttpSession session = r.getSession(false);
                    if (session != null) {
                        r.changeSessionId();
                    } else {
                        session = r.getSession(true);
                    }
                    session.setAttribute("uid", rs.getLong(1));
                    session.setAttribute("name", rs.getString(2));
                    session.setAttribute("role", rs.getString(3));
                    session.setMaxInactiveInterval(1800);
                    String csrfToken = UUID.randomUUID().toString();
                    session.setAttribute("csrf", csrfToken);

                    log(c, rs.getLong(1), "LOGIN", "Successful login");
                    Json.send(s, 200, Json.obj(
                        "message", "Signed in",
                        "role", rs.getString(3),
                        "csrf", csrfToken
                    ));
                }
            } catch (Exception e) {
                getServletContext().log("Login failed", e);
                Json.send(s, 500, Json.obj("error", "Login failed: " + (e.getMessage() != null ? e.getMessage() : "check database configuration")));
            }
        } else if ("/logout".equals(path)) {
            HttpSession h = r.getSession(false);
            if (h != null) {
                h.invalidate();
            }
            Json.send(s, 200, Json.obj("message", "Signed out"));
        } else {
            s.sendError(404);
        }
    }

    static void log(Connection c, long actor, String type, String desc) {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO activity_logs(actor_id, event_type, description) VALUES(?,?,?)")) {
            if (actor == 0) {
                p.setNull(1, Types.BIGINT);
            } else {
                p.setLong(1, actor);
            }
            p.setString(2, type);
            p.setString(3, desc);
            p.executeUpdate();
        } catch (Exception ignored) {}
    }
}
