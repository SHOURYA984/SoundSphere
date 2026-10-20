package com.soundsphere.servlet;

import com.soundsphere.config.Db;
import com.soundsphere.util.*;
import jakarta.servlet.annotation.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

@WebServlet("/api/music-files/*")
@MultipartConfig(maxFileSize = 26214400L, maxRequestSize = 27262976L)
public class MusicServlet extends HttpServlet {
    private Path dir() {
        String d = System.getenv("SOUNDSPHERE_UPLOAD_DIR");
        if (d == null || d.isBlank()) d = System.getProperty("SOUNDSPHERE_UPLOAD_DIR");
        if (d == null || d.isBlank()) d = "D:/soundsphere-uploads";
        Path path = Paths.get(d).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
        } catch (Exception ignored) {}
        return path;
    }

    protected void doGet(HttpServletRequest r, HttpServletResponse s) throws IOException {
        boolean pub = "true".equals(r.getParameter("public"));
        String p = r.getPathInfo();

        if (p == null || p.equals("/")) {
            if (!Security.requireUser(r, s)) return;
            long uid = Security.uid(r);
            try (Connection c = Db.get();
                 PreparedStatement q = c.prepareStatement(
                     "SELECT id,title,description,original_name,size_bytes,mime_type,moderation_status,created_at,owner_id " +
                     "FROM music_files WHERE owner_id=? OR (project_id IN (SELECT project_id FROM project_members WHERE user_id=?)) " +
                     "ORDER BY id DESC")) {
                q.setLong(1, uid);
                q.setLong(2, uid);
                try (ResultSet rs = q.executeQuery()) {
                    List<Map<String, Object>> a = new ArrayList<>();
                    while (rs.next()) {
                        a.add(Json.obj(
                            "id", rs.getLong(1),
                            "title", rs.getString(2),
                            "description", rs.getString(3),
                            "originalName", rs.getString(4),
                            "sizeBytes", rs.getLong(5),
                            "mimeType", rs.getString(6),
                            "status", rs.getString(7),
                            "createdAt", rs.getTimestamp(8).toString(),
                            "ownerId", rs.getLong(9)
                        ));
                    }
                    Json.send(s, 200, a);
                }
            } catch (Exception e) {
                getServletContext().log("Music list failed", e);
                s.sendError(500);
            }
            return;
        }

        try (Connection c = Db.get()) {
            String[] z = p.split("/");
            if (z.length < 2) {
                s.sendError(404);
                return;
            }
            long id = Long.parseLong(z[1]);
            long uid = Security.uid(r);

            try (PreparedStatement q = c.prepareStatement(
                "SELECT owner_id,storage_name,mime_type,original_name,moderation_status,project_id FROM music_files WHERE id=?")) {
                q.setLong(1, id);
                try (ResultSet rs = q.executeQuery()) {
                    if (!rs.next()) {
                        s.sendError(404);
                        return;
                    }
                    long owner = rs.getLong(1);
                    Object proj = rs.getObject(6);
                    boolean member = proj != null && isMember(c, rs.getLong(6), uid);
                    boolean isApprovedPublic = "APPROVED".equals(rs.getString(5)) && pub;

                    if (owner != uid && !member && !"ADMIN".equals(Security.role(r)) && !isApprovedPublic) {
                        s.sendError(403);
                        return;
                    }

                    Path file = dir().resolve(rs.getString(2)).normalize();
                    if (!file.startsWith(dir()) || !Files.exists(file)) {
                        s.sendError(404);
                        return;
                    }

                    s.setContentType(rs.getString(3));
                    if (z.length > 2 && z[2].equals("download")) {
                        s.setHeader("Content-Disposition", "attachment; filename=\"" + rs.getString(4).replace("\"", "") + "\"");
                    } else {
                        s.setHeader("Content-Disposition", "inline");
                    }
                    Files.copy(file, s.getOutputStream());
                }
            }
        } catch (NumberFormatException e) {
            s.sendError(400);
        } catch (Exception e) {
            getServletContext().log("Music access failed", e);
            s.sendError(500);
        }
    }

    private boolean isMember(Connection c, long pid, long uid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM project_members WHERE project_id=? AND user_id=?")) {
            p.setLong(1, pid);
            p.setLong(2, uid);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next();
            }
        }
    }

    protected void doPost(HttpServletRequest r, HttpServletResponse s) throws IOException {
        if (!Security.requireUser(r, s)) return;
        long uid = Security.uid(r);
        String p = r.getPathInfo();
        if (p != null && !p.equals("/") && !p.isBlank()) {
            s.sendError(404);
            return;
        }

        try {
            Part part = r.getPart("audio");
            String title = r.getParameter("title");
            if (part == null || part.getSize() == 0 || title == null || title.isBlank()) {
                Json.send(s, 400, Json.obj("error", "Title and audio file are required"));
                return;
            }

            String original = Paths.get(part.getSubmittedFileName() == null ? "audio.bin" : part.getSubmittedFileName()).getFileName().toString();
            String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!Set.of("mp3", "wav", "ogg", "m4a").contains(ext)) {
                Json.send(s, 400, Json.obj("error", "Allowed formats: mp3, wav, ogg, m4a"));
                return;
            }

            if (part.getSize() > 25L * 1024 * 1024) {
                Json.send(s, 413, Json.obj("error", "Maximum upload is 25 MB"));
                return;
            }

            Files.createDirectories(dir());
            String storage = UUID.randomUUID() + "." + ext;
            Path target = dir().resolve(storage).normalize();
            if (!target.startsWith(dir())) {
                s.sendError(400);
                return;
            }

            try (InputStream in = part.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            String mime = part.getContentType() == null ? "application/octet-stream" : part.getContentType();
            try (Connection c = Db.get();
                 PreparedStatement q = c.prepareStatement(
                     "INSERT INTO music_files(owner_id,title,description,original_name,storage_name,mime_type,size_bytes,moderation_status) VALUES(?,?,?,?,?,?,?,'PENDING')",
                     Statement.RETURN_GENERATED_KEYS)) {
                q.setLong(1, uid);
                q.setString(2, title.trim());
                q.setString(3, Optional.ofNullable(r.getParameter("description")).orElse(""));
                q.setString(4, original);
                q.setString(5, storage);
                q.setString(6, mime);
                q.setLong(7, part.getSize());
                q.executeUpdate();

                try (ResultSet k = q.getGeneratedKeys()) {
                    k.next();
                    Json.send(s, 201, Json.obj("id", k.getLong(1), "message", "Uploaded; awaiting moderation"));
                }
            }
        } catch (Exception e) {
            getServletContext().log("Upload failed", e);
            Json.send(s, 500, Json.obj("error", "Upload failed; verify storage permissions and database settings"));
        }
    }

    protected void doDelete(HttpServletRequest r, HttpServletResponse s) throws IOException {
        if (!Security.requireUser(r, s)) return;
        String p = r.getPathInfo();
        if (p == null || p.length() <= 1) {
            s.sendError(404);
            return;
        }

        try {
            long id = Long.parseLong(p.substring(1));
            try (Connection c = Db.get();
                 PreparedStatement q = c.prepareStatement("SELECT owner_id,storage_name FROM music_files WHERE id=?")) {
                q.setLong(1, id);
                try (ResultSet rs = q.executeQuery()) {
                    if (!rs.next()) {
                        s.sendError(404);
                        return;
                    }
                    if (rs.getLong(1) != Security.uid(r) && !"ADMIN".equals(Security.role(r))) {
                        s.sendError(403);
                        return;
                    }
                    String name = rs.getString(2);
                    try (PreparedStatement d = c.prepareStatement("DELETE FROM music_files WHERE id=?")) {
                        d.setLong(1, id);
                        d.executeUpdate();
                    }
                    Files.deleteIfExists(dir().resolve(name));
                    Json.send(s, 200, Json.obj("message", "File deleted"));
                }
            }
        } catch (NumberFormatException e) {
            s.sendError(400);
        } catch (Exception e) {
            getServletContext().log("Delete music file failed", e);
            Json.send(s, 500, Json.obj("error", "Could not delete file"));
        }
    }
}
