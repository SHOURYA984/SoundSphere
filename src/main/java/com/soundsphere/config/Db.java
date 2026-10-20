package com.soundsphere.config;
import java.io.InputStream;
import java.sql.*;
import java.util.Properties;

public final class Db {
 private static String dbUrl;
 private static String dbUser;
 private static String dbPass;

 static {
  try {
   Class.forName("com.mysql.cj.jdbc.Driver");
  } catch (ClassNotFoundException ignored) {}

  Properties props = new Properties();
  try (InputStream in = Db.class.getClassLoader().getResourceAsStream("application.properties")) {
   if (in != null) {
    props.load(in);
   }
  } catch (Exception ignored) {}

  String defaultUrl = props.getProperty("db.url", "jdbc:mysql://localhost:3306/soundsphere_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
  String defaultUser = props.getProperty("db.username", "root");
  String defaultPass = props.getProperty("db.password", "Abhishek@12");

  dbUrl = env("SOUNDSPHERE_DB_URL", defaultUrl);
  dbUser = env("SOUNDSPHERE_DB_USER", defaultUser);
  dbPass = env("SOUNDSPHERE_DB_PASSWORD", defaultPass);
 }

 private Db() {}

 public static Connection get() throws SQLException {
  return DriverManager.getConnection(dbUrl, dbUser, dbPass);
 }

 private static String env(String k, String d) {
  String v = System.getenv(k);
  if (v == null || v.isBlank()) {
   v = System.getProperty(k);
  }
  return (v == null || v.isBlank()) ? d : v;
 }
}
