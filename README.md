# SoundSphere — Online Music Collaboration Platform

Java 17 + Jakarta Servlets (Tomcat 10.1) + JDBC + MySQL 8 + HTML/CSS/vanilla JS.

## Important scope note
This is a runnable full-stack foundation with working authentication, role-based access, project CRUD, collaboration membership, project messages, task CRUD, notifications, portfolio CRUD, admin user management, admin moderation for portfolio items, settings, activity logs, and secured audio upload/streaming. It is not a claim that every production-grade feature in the long specification has been independently penetration-tested. File versioning and sophisticated real-time transport are not included in this starter build.

## Requirements
- JDK 17+
- Maven 3.9+
- MySQL 8+
- Apache Tomcat 10.1
- Configure a MySQL user/database before deployment.

## Setup (Windows)
1. Create the database: open MySQL Workbench and run `database/schema.sql`.
2. Set environment variables before starting Tomcat:
   - `SOUNDSPHERE_DB_URL=jdbc:mysql://localhost:3306/soundsphere_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
   - `SOUNDSPHERE_DB_USER=your_mysql_user`
   - `SOUNDSPHERE_DB_PASSWORD=your_mysql_password`
   - `SOUNDSPHERE_UPLOAD_DIR=D:/soundsphere-uploads`
3. From the project folder run: `mvn clean package`
4. Copy `target\\soundsphere.war` to Tomcat's `webapps` folder and start Tomcat.
5. Open `http://localhost:8080/soundsphere/`.
6. Register a musician account.
7. Create the first Admin using the documented one-time SQL promotion only after registering your account:
   `UPDATE users SET role='ADMIN' WHERE email='your-email@example.com';`
   Then log out and log in again. Do not expose this query in a public web page.
8. Restart Tomcat after changing environment variables.

## Upload storage
Audio files are saved outside the web application at the directory configured by `SOUNDSPHERE_UPLOAD_DIR` (default: `${user.home}/soundsphere-uploads`). The database stores generated storage names, not client paths.

## Included functionality
- Registration, BCrypt password hashing, login/logout, session auth, role checks.
- Project creation/list/update/delete; project members; messages; tasks.
- Music file upload, secured playback/download, metadata editing, deletion.
- Portfolio items and moderation status.
- Notifications, user profile, admin user management, settings, activity feed.
- Database-backed dashboard summaries and search/filter controls.

## API
All API paths use `/api/...`; sessions are cookie-based. State-changing requests require the `X-CSRF-Token` header matching the session token. The frontend helper adds this header automatically.

## Troubleshooting
- `Communications link failure`: check MySQL service, DB URL, credentials, and database creation.
- `Access denied for user`: grant the configured MySQL account privileges on `soundsphere_db`.
- `404 /soundsphere`: ensure `soundsphere.war` is in Tomcat `webapps` and wait for deployment.
- `401`: log in again; sessions expire.
- `403`: check role/project membership and CSRF token.
- Upload errors: check upload directory permissions and configured upload size.
