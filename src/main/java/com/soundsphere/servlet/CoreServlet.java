package com.soundsphere.servlet;

import com.soundsphere.config.Db;
import com.soundsphere.util.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;

@WebServlet("/api/*")
public class CoreServlet extends HttpServlet {
    protected void service(HttpServletRequest r, HttpServletResponse s) throws IOException, jakarta.servlet.ServletException {
        String path = r.getRequestURI().substring(r.getContextPath().length());
        try {
            if (path.equals("/api/dashboard") && "GET".equals(r.getMethod())) {
                if (!Security.requireUser(r, s)) return;
                dashboard(r, s);
                return;
            }
            if (path.equals("/api/projects") || path.startsWith("/api/projects/")) {
                projects(r, s, path);
                return;
            }
            if (path.equals("/api/portfolio") || path.startsWith("/api/portfolio")) {
                portfolio(r, s, path);
                return;
            }
            if (path.equals("/api/notifications") || path.startsWith("/api/notifications/")) {
                notifications(r, s, path);
                return;
            }
            if (path.equals("/api/profile")) {
                profile(r, s);
                return;
            }
            if (path.equals("/api/admin/users") || path.startsWith("/api/admin/users/")) {
                users(r, s, path);
                return;
            }
            if (path.equals("/api/admin/settings")) {
                settings(r, s);
                return;
            }
            if (path.equals("/api/admin/activity")) {
                activity(r, s);
                return;
            }
            if (path.equals("/api/admin/moderation") || path.startsWith("/api/admin/moderation/")) {
                moderation(r, s, path);
                return;
            }
            s.sendError(404, "Unknown API endpoint");
        } catch (Exception e) {
            getServletContext().log("API error " + path, e);
            Json.send(s, 500, Json.obj("error", "Request failed: " + (e.getMessage() != null ? e.getMessage() : "server error")));
        }
    }

    private void dashboard(HttpServletRequest r, HttpServletResponse s) throws Exception {
        long uid = Security.uid(r);
        try (Connection c = Db.get()) {
            if ("ADMIN".equals(Security.role(r))) {
                Json.send(s, 200, Json.obj(
                    "users", count(c, "users"),
                    "projects", count(c, "projects"),
                    "files", count(c, "music_files"),
                    "pendingModeration", scalar(c, "SELECT (SELECT COUNT(*) FROM music_files WHERE moderation_status='PENDING')+(SELECT COUNT(*) FROM portfolio_items WHERE moderation_status='PENDING')"),
                    "role", "ADMIN"
                ));
            } else {
                try (PreparedStatement p = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM projects p JOIN project_members m ON p.id=m.project_id WHERE m.user_id=?), " +
                    "(SELECT COUNT(*) FROM music_files WHERE owner_id=?), " +
                    "(SELECT COUNT(*) FROM portfolio_items WHERE owner_id=?), " +
                    "(SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=0)")) {
                    for (int i = 1; i <= 4; i++) p.setLong(i, uid);
                    try (ResultSet rs = p.executeQuery()) {
                        rs.next();
                        Json.send(s, 200, Json.obj(
                            "projects", rs.getLong(1),
                            "files", rs.getLong(2),
                            "portfolio", rs.getLong(3),
                            "unreadNotifications", rs.getLong(4),
                            "role", "MUSICIAN"
                        ));
                    }
                }
            }
        }
    }

    private long count(Connection c, String t) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM " + t);
    }

    private long scalar(Connection c, String q) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean member(Connection c, long project, long uid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM project_members WHERE project_id=? AND user_id=?")) {
            p.setLong(1, project);
            p.setLong(2, uid);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void projects(HttpServletRequest r, HttpServletResponse s, String path) throws Exception {
        if (!Security.requireUser(r, s)) return;
        long uid = Security.uid(r);
        String method = r.getMethod();

        if (path.equals("/api/projects") && method.equals("GET")) {
            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement(
                     "SELECT p.id,p.title,p.description,p.owner_id,p.deadline,p.status,p.created_at,u.name owner_name " +
                     "FROM projects p JOIN project_members m ON p.id=m.project_id JOIN users u ON u.id=p.owner_id " +
                     "WHERE m.user_id=? ORDER BY p.updated_at DESC")) {
                p.setLong(1, uid);
                try (ResultSet rs = p.executeQuery()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(Json.obj(
                            "id", rs.getLong("id"),
                            "title", rs.getString("title"),
                            "description", rs.getString("description"),
                            "ownerId", rs.getLong("owner_id"),
                            "deadline", rs.getString("deadline"),
                            "status", rs.getString("status"),
                            "createdAt", rs.getTimestamp("created_at").toString(),
                            "ownerName", rs.getString("owner_name")
                        ));
                    }
                    Json.send(s, 200, out);
                }
            }
            return;
        }

        if (path.equals("/api/projects") && method.equals("POST")) {
            Map<String, Object> b = Json.body(r);
            String title = Json.str(b, "title");
            if (title.isBlank() || title.length() > 180) {
                Json.send(s, 400, Json.obj("error", "Project title is required (max 180 characters)"));
                return;
            }
            try (Connection c = Db.get()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement("INSERT INTO projects(title,description,owner_id,deadline,status) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, title);
                    p.setString(2, Json.str(b, "description"));
                    p.setLong(3, uid);
                    String d = Json.str(b, "deadline");
                    if (d.isBlank()) p.setNull(4, Types.DATE);
                    else p.setDate(4, java.sql.Date.valueOf(d));
                    p.setString(5, "PLANNING");
                    p.executeUpdate();
                    try (ResultSet k = p.getGeneratedKeys()) {
                        k.next();
                        long id = k.getLong(1);
                        try (PreparedStatement m = c.prepareStatement("INSERT INTO project_members(project_id,user_id,member_role) VALUES(?,?,'OWNER')")) {
                            m.setLong(1, id);
                            m.setLong(2, uid);
                            m.executeUpdate();
                        }
                        c.commit();
                        Json.send(s, 201, Json.obj("id", id, "message", "Project created"));
                    }
                } catch (Exception ex) {
                    c.rollback();
                    throw ex;
                }
            }
            return;
        }

        String[] parts = path.split("/");
        if (parts.length < 4) {
            s.sendError(404);
            return;
        }
        long id = Long.parseLong(parts[3]);

        try (Connection c = Db.get()) {
            if (!member(c, id, uid) && !"ADMIN".equals(Security.role(r))) {
                s.sendError(403, "Project membership required");
                return;
            }

            if (parts.length == 5 && parts[4].equals("messages")) {
                if (method.equals("GET")) {
                    try (PreparedStatement p = c.prepareStatement("SELECT m.id,m.message,m.created_at,u.name author FROM project_messages m JOIN users u ON u.id=m.author_id WHERE m.project_id=? ORDER BY m.id")) {
                        p.setLong(1, id);
                        try (ResultSet rs = p.executeQuery()) {
                            List<Map<String, Object>> a = new ArrayList<>();
                            while (rs.next()) {
                                a.add(Json.obj("id", rs.getLong(1), "message", rs.getString(2), "createdAt", rs.getTimestamp(3).toString(), "author", rs.getString(4)));
                            }
                            Json.send(s, 200, a);
                        }
                    }
                } else if (method.equals("POST")) {
                    Map<String, Object> b = Json.body(r);
                    String msg = Json.str(b, "message");
                    if (msg.isBlank() || msg.length() > 4000) {
                        Json.send(s, 400, Json.obj("error", "Message must contain 1–4000 characters"));
                        return;
                    }
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO project_messages(project_id,author_id,message) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        p.setLong(1, id);
                        p.setLong(2, uid);
                        p.setString(3, msg);
                        p.executeUpdate();
                        Json.send(s, 201, Json.obj("message", "Message posted"));
                    }
                }
                return;
            }

            if (parts.length == 5 && parts[4].equals("tasks")) {
                if (method.equals("GET")) {
                    try (PreparedStatement p = c.prepareStatement("SELECT id,title,description,assignee_id,due_date,priority,status FROM project_tasks WHERE project_id=? ORDER BY id DESC")) {
                        p.setLong(1, id);
                        try (ResultSet rs = p.executeQuery()) {
                            List<Map<String, Object>> a = new ArrayList<>();
                            while (rs.next()) {
                                a.add(Json.obj("id", rs.getLong(1), "title", rs.getString(2), "description", rs.getString(3), "assigneeId", rs.getObject(4), "dueDate", rs.getString(5), "priority", rs.getString(6), "status", rs.getString(7)));
                            }
                            Json.send(s, 200, a);
                        }
                    }
                } else if (method.equals("POST")) {
                    Map<String, Object> b = Json.body(r);
                    String title = Json.str(b, "title");
                    if (title.isBlank()) {
                        Json.send(s, 400, Json.obj("error", "Task title is required"));
                        return;
                    }
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO project_tasks(project_id,title,description,due_date,priority,status,created_by) VALUES(?,?,?,?,?,'TODO',?)")) {
                        p.setLong(1, id);
                        p.setString(2, title);
                        p.setString(3, Json.str(b, "description"));
                        String d = Json.str(b, "dueDate");
                        if (d.isBlank()) p.setNull(4, Types.DATE);
                        else p.setDate(4, java.sql.Date.valueOf(d));
                        p.setString(5, Set.of("LOW", "MEDIUM", "HIGH").contains(Json.str(b, "priority")) ? Json.str(b, "priority") : "MEDIUM");
                        p.setLong(6, uid);
                        p.executeUpdate();
                        Json.send(s, 201, Json.obj("message", "Task created"));
                    }
                }
                return;
            }

            if (parts.length == 4 && method.equals("PATCH")) {
                Map<String, Object> b = Json.body(r);
                String status = Json.str(b, "status");
                if (!Set.of("PLANNING", "IN_PROGRESS", "ON_HOLD", "COMPLETED").contains(status)) {
                    Json.send(s, 400, Json.obj("error", "Invalid project status"));
                    return;
                }
                try (PreparedStatement p = c.prepareStatement("UPDATE projects SET status=?,title=COALESCE(NULLIF(?,''),title),description=? WHERE id=? AND (owner_id=? OR ?)")) {
                    p.setString(1, status);
                    p.setString(2, Json.str(b, "title"));
                    p.setString(3, Json.str(b, "description"));
                    p.setLong(4, id);
                    p.setLong(5, uid);
                    p.setBoolean(6, "ADMIN".equals(Security.role(r)));
                    p.executeUpdate();
                    Json.send(s, 200, Json.obj("message", "Project updated"));
                }
                return;
            }

            if (parts.length == 4 && method.equals("DELETE")) {
                try (PreparedStatement p = c.prepareStatement("DELETE FROM projects WHERE id=? AND owner_id=?")) {
                    p.setLong(1, id);
                    p.setLong(2, uid);
                    if (p.executeUpdate() == 0) {
                        s.sendError(403, "Only the owner can delete this project");
                        return;
                    }
                    Json.send(s, 200, Json.obj("message", "Project deleted"));
                }
                return;
            }
        }
        s.sendError(405);
    }

    private void portfolio(HttpServletRequest r, HttpServletResponse s, String path) throws Exception {
        boolean pub = "true".equals(r.getParameter("public"));
        if (!pub && !Security.requireUser(r, s)) return;
        long uid = Security.uid(r);

        if (r.getMethod().equals("GET")) {
            String sql = pub
                ? "SELECT p.id,p.owner_id,p.title,p.description,p.item_type,p.genre,p.visibility,p.moderation_status,u.name owner FROM portfolio_items p JOIN users u ON u.id=p.owner_id WHERE p.visibility='PUBLIC' AND p.moderation_status='APPROVED' ORDER BY p.created_at DESC"
                : "SELECT id,owner_id,title,description,item_type,genre,visibility,moderation_status FROM portfolio_items WHERE owner_id=? ORDER BY updated_at DESC";
            try (Connection c = Db.get(); PreparedStatement p = c.prepareStatement(sql)) {
                if (!pub) p.setLong(1, uid);
                try (ResultSet rs = p.executeQuery()) {
                    List<Map<String, Object>> a = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> x = Json.obj(
                            "id", rs.getLong(1),
                            "ownerId", rs.getLong(2),
                            "title", rs.getString(3),
                            "description", rs.getString(4),
                            "type", rs.getString(5),
                            "genre", rs.getString(6),
                            "visibility", rs.getString(7),
                            "moderationStatus", rs.getString(8)
                        );
                        if (pub) x.put("owner", rs.getString(9));
                        a.add(x);
                    }
                    Json.send(s, 200, a);
                }
            }
            return;
        }

        if (r.getMethod().equals("POST") && path.equals("/api/portfolio")) {
            Map<String, Object> b = Json.body(r);
            String title = Json.str(b, "title");
            if (title.isBlank()) {
                Json.send(s, 400, Json.obj("error", "Title is required"));
                return;
            }
            String vis = "PUBLIC".equals(Json.str(b, "visibility")) ? "PUBLIC" : "PRIVATE";
            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("INSERT INTO portfolio_items(owner_id,title,description,item_type,genre,visibility,moderation_status) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, uid);
                p.setString(2, title);
                p.setString(3, Json.str(b, "description"));
                p.setString(4, "ALBUM".equals(Json.str(b, "type")) ? "ALBUM" : "SONG");
                p.setString(5, Json.str(b, "genre"));
                p.setString(6, vis);
                p.setString(7, vis.equals("PUBLIC") ? "PENDING" : "APPROVED");
                p.executeUpdate();
                try (ResultSet k = p.getGeneratedKeys()) {
                    k.next();
                    Json.send(s, 201, Json.obj("id", k.getLong(1), "message", "Portfolio item created"));
                }
            }
            return;
        }

        String[] ps = path.split("/");
        if (ps.length == 5 && r.getMethod().equals("DELETE")) {
            long id = Long.parseLong(ps[4]);
            try (Connection c = Db.get(); PreparedStatement p = c.prepareStatement("DELETE FROM portfolio_items WHERE id=? AND owner_id=?")) {
                p.setLong(1, id);
                p.setLong(2, uid);
                if (p.executeUpdate() == 0) s.sendError(404);
                else Json.send(s, 200, Json.obj("message", "Portfolio item deleted"));
            }
            return;
        }
        s.sendError(405);
    }

    private void notifications(HttpServletRequest r, HttpServletResponse s, String path) throws Exception {
        if (!Security.requireUser(r, s)) return;
        long uid = Security.uid(r);

        if (r.getMethod().equals("GET")) {
            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("SELECT id,title,body,target_url,is_read,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 100")) {
                p.setLong(1, uid);
                try (ResultSet rs = p.executeQuery()) {
                    List<Map<String, Object>> a = new ArrayList<>();
                    while (rs.next()) {
                        a.add(Json.obj("id", rs.getLong(1), "title", rs.getString(2), "body", rs.getString(3), "targetUrl", rs.getString(4), "read", rs.getBoolean(5), "createdAt", rs.getTimestamp(6).toString()));
                    }
                    Json.send(s, 200, a);
                }
            }
            return;
        }

        String[] ps = path.split("/");
        if (r.getMethod().equals("POST") && ps.length == 4 && ps[3].equals("read-all")) {
            try (Connection c = Db.get(); PreparedStatement p = c.prepareStatement("UPDATE notifications SET is_read=1 WHERE user_id=?")) {
                p.setLong(1, uid);
                p.executeUpdate();
                Json.send(s, 200, Json.obj("message", "Notifications marked read"));
            }
            return;
        }

        if (r.getMethod().equals("PATCH") && ps.length == 4) {
            try (Connection c = Db.get(); PreparedStatement p = c.prepareStatement("UPDATE notifications SET is_read=1 WHERE id=? AND user_id=?")) {
                p.setLong(1, Long.parseLong(ps[3]));
                p.setLong(2, uid);
                p.executeUpdate();
                Json.send(s, 200, Json.obj("message", "Notification marked read"));
            }
            return;
        }
        s.sendError(405);
    }

    private void profile(HttpServletRequest r, HttpServletResponse s) throws Exception {
        if (!Security.requireUser(r, s)) return;
        long uid = Security.uid(r);

        if (r.getMethod().equals("GET")) {
            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("SELECT u.id,u.name,u.email,u.role,COALESCE(pr.bio,''),COALESCE(pr.genres,''),COALESCE(pr.instruments,'') FROM users u LEFT JOIN user_profiles pr ON pr.user_id=u.id WHERE u.id=?")) {
                p.setLong(1, uid);
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) Json.send(s, 200, Json.obj("id", rs.getLong(1), "name", rs.getString(2), "email", rs.getString(3), "role", rs.getString(4), "bio", rs.getString(5), "genres", rs.getString(6), "instruments", rs.getString(7)));
                    else s.sendError(404);
                }
            }
            return;
        }

        if (r.getMethod().equals("PUT")) {
            Map<String, Object> b = Json.body(r);
            String name = Json.str(b, "name");
            if (name.length() < 2 || name.length() > 120) {
                Json.send(s, 400, Json.obj("error", "Name must be 2–120 characters"));
                return;
            }
            try (Connection c = Db.get()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement("UPDATE users SET name=? WHERE id=?")) {
                    p.setString(1, name);
                    p.setLong(2, uid);
                    p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO user_profiles(user_id,bio,genres,instruments) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE bio=VALUES(bio),genres=VALUES(genres),instruments=VALUES(instruments)")) {
                    p.setLong(1, uid);
                    p.setString(2, Json.str(b, "bio"));
                    p.setString(3, Json.str(b, "genres"));
                    p.setString(4, Json.str(b, "instruments"));
                    p.executeUpdate();
                }
                c.commit();
                Json.send(s, 200, Json.obj("message", "Profile updated"));
            }
            return;
        }
        s.sendError(405);
    }

    private void users(HttpServletRequest r, HttpServletResponse s, String path) throws Exception {
        if (!Security.requireAdmin(r, s)) return;
        long current = Security.uid(r);

        if (path.equals("/api/admin/users") && r.getMethod().equals("GET")) {
            try (Connection c = Db.get();
                 PreparedStatement p = c.prepareStatement("SELECT id,name,email,role,status,created_at FROM users WHERE (?='' OR role=?) AND (?='' OR status=?) AND (name LIKE ? OR email LIKE ?) ORDER BY id DESC LIMIT 250")) {
                String role = Optional.ofNullable(r.getParameter("role")).orElse("");
                String status = Optional.ofNullable(r.getParameter("status")).orElse("");
                String q = "%" + Optional.ofNullable(r.getParameter("q")).orElse("") + "%";
                p.setString(1, role);
                p.setString(2, role);
                p.setString(3, status);
                p.setString(4, status);
                p.setString(5, q);
                p.setString(6, q);
                try (ResultSet rs = p.executeQuery()) {
                    List<Map<String, Object>> a = new ArrayList<>();
                    while (rs.next()) {
                        a.add(Json.obj("id", rs.getLong(1), "name", rs.getString(2), "email", rs.getString(3), "role", rs.getString(4), "status", rs.getString(5), "createdAt", rs.getTimestamp(6).toString()));
                    }
                    Json.send(s, 200, a);
                }
            }
            return;
        }

        String[] ps = path.split("/");
        if (ps.length == 5 && r.getMethod().equals("PATCH")) {
            long id = Long.parseLong(ps[4]);
            Map<String, Object> b = Json.body(r);
            String role = Json.str(b, "role"), status = Json.str(b, "status");
            if (!Set.of("ADMIN", "MUSICIAN").contains(role) || !Set.of("ACTIVE", "DISABLED").contains(status)) {
                Json.send(s, 400, Json.obj("error", "Invalid role or status"));
                return;
            }
            try (Connection c = Db.get()) {
                if (id == current && (!status.equals("ACTIVE") || !role.equals("ADMIN"))) {
                    Json.send(s, 400, Json.obj("error", "Cannot demote or disable your current Admin account"));
                    return;
                }
                boolean targetIsActiveAdmin = false;
                try (PreparedStatement check = c.prepareStatement("SELECT role,status FROM users WHERE id=?")) {
                    check.setLong(1, id);
                    try (ResultSet cr = check.executeQuery()) {
                        if (cr.next()) targetIsActiveAdmin = "ADMIN".equals(cr.getString(1)) && "ACTIVE".equals(cr.getString(2));
                    }
                }
                if (targetIsActiveAdmin && (!role.equals("ADMIN") || !status.equals("ACTIVE"))) {
                    long admins = scalar(c, "SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'");
                    if (admins <= 1) {
                        Json.send(s, 400, Json.obj("error", "Cannot remove the final active Admin"));
                        return;
                    }
                }
                try (PreparedStatement p = c.prepareStatement("UPDATE users SET role=?,status=? WHERE id=?")) {
                    p.setString(1, role);
                    p.setString(2, status);
                    p.setLong(3, id);
                    if (p.executeUpdate() == 0) {
                        s.sendError(404);
                        return;
                    }
                    Json.send(s, 200, Json.obj("message", "User updated"));
                }
            }
            return;
        }

        if (ps.length == 5 && r.getMethod().equals("DELETE")) {
            long id = Long.parseLong(ps[4]);
            if (id == current) {
                Json.send(s, 400, Json.obj("error", "Cannot delete your own account"));
                return;
            }
            try (Connection c = Db.get(); PreparedStatement p = c.prepareStatement("DELETE FROM users WHERE id=?")) {
                p.setLong(1, id);
                if (p.executeUpdate() == 0) s.sendError(404);
                else Json.send(s, 200, Json.obj("message", "User deleted"));
            }
            return;
        }
        s.sendError(405);
    }

    private void settings(HttpServletRequest r, HttpServletResponse s) throws Exception {
        if (!Security.requireAdmin(r, s)) return;
        if (r.getMethod().equals("GET")) {
            try (Connection c = Db.get(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT setting_key,setting_value FROM system_settings ORDER BY setting_key")) {
                Map<String, String> m = new LinkedHashMap<>();
                while (rs.next()) m.put(rs.getString(1), rs.getString(2));
                Json.send(s, 200, m);
            }
            return;
        }
        if (r.getMethod().equals("PUT")) {
            Map<String, Object> b = Json.body(r);
            try (Connection c = Db.get()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement("INSERT INTO system_settings(setting_key,setting_value,updated_by) VALUES(?,?,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),updated_by=VALUES(updated_by)")) {
                    for (String k : b.keySet()) {
                        String v = String.valueOf(b.get(k));
                        if (v.length() > 2000) continue;
                        p.setString(1, k);
                        p.setString(2, v);
                        p.setLong(3, Security.uid(r));
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
                Json.send(s, 200, Json.obj("message", "Settings saved"));
            }
            return;
        }
        s.sendError(405);
    }

    private void activity(HttpServletRequest r, HttpServletResponse s) throws Exception {
        if (!Security.requireAdmin(r, s)) return;
        if (!r.getMethod().equals("GET")) {
            s.sendError(405);
            return;
        }
        try (Connection c = Db.get();
             PreparedStatement p = c.prepareStatement("SELECT a.id,a.event_type,a.description,a.created_at,u.name FROM activity_logs a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 200");
             ResultSet rs = p.executeQuery()) {
            List<Map<String, Object>> a = new ArrayList<>();
            while (rs.next()) {
                a.add(Json.obj("id", rs.getLong(1), "type", rs.getString(2), "description", rs.getString(3), "createdAt", rs.getTimestamp(4).toString(), "actor", rs.getString(5)));
            }
            Json.send(s, 200, a);
        }
    }

    private void moderation(HttpServletRequest r, HttpServletResponse s, String path) throws Exception {
        if (!Security.requireAdmin(r, s)) return;
        if (path.equals("/api/admin/moderation") && r.getMethod().equals("GET")) {
            try (Connection c = Db.get();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT 'PORTFOLIO_ITEM' type,p.id,p.title,p.moderation_status status,u.name owner,p.created_at FROM portfolio_items p JOIN users u ON u.id=p.owner_id WHERE p.moderation_status='PENDING' " +
                     "UNION ALL SELECT 'MUSIC_FILE',f.id,f.title,f.moderation_status,u.name,f.created_at FROM music_files f JOIN users u ON u.id=f.owner_id WHERE f.moderation_status='PENDING' ORDER BY created_at DESC")) {
                List<Map<String, Object>> a = new ArrayList<>();
                while (rs.next()) {
                    a.add(Json.obj("type", rs.getString(1), "id", rs.getLong(2), "title", rs.getString(3), "status", rs.getString(4), "owner", rs.getString(5), "createdAt", rs.getTimestamp(6).toString()));
                }
                Json.send(s, 200, a);
            }
            return;
        }

        String[] ps = path.split("/");
        if (ps.length == 6 && r.getMethod().equals("POST")) {
            String type = ps[4];
            long id = Long.parseLong(ps[5]);
            Map<String, Object> b = Json.body(r);
            String decision = Json.str(b, "decision"), reason = Json.str(b, "reason");
            if (!Set.of("APPROVED", "REJECTED").contains(decision) || (decision.equals("REJECTED") && reason.isBlank())) {
                Json.send(s, 400, Json.obj("error", "Choose approve or reject; rejection requires a reason"));
                return;
            }
            String table = type.equals("portfolio") ? "portfolio_items" : type.equals("music") ? "music_files" : null;
            if (table == null) {
                s.sendError(404);
                return;
            }
            try (Connection c = Db.get()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement("UPDATE " + table + " SET moderation_status=?,rejection_reason=? WHERE id=?")) {
                    p.setString(1, decision);
                    p.setString(2, decision.equals("REJECTED") ? reason : null);
                    p.setLong(3, id);
                    if (p.executeUpdate() == 0) {
                        s.sendError(404);
                        return;
                    }
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO content_moderation(content_type,content_id,admin_id,decision,reason) VALUES(?,?,?,?,?)")) {
                    p.setString(1, type.equals("portfolio") ? "PORTFOLIO_ITEM" : "MUSIC_FILE");
                    p.setLong(2, id);
                    p.setLong(3, Security.uid(r));
                    p.setString(4, decision);
                    p.setString(5, reason);
                    p.executeUpdate();
                }
                c.commit();
                Json.send(s, 200, Json.obj("message", "Moderation decision saved"));
            }
            return;
        }
        s.sendError(405);
    }
}
