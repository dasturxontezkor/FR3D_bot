package org.example;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.telegram.telegrambots.meta.api.methods.GetFile;

import java.io.File;
import java.io.FileInputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {

    private final String url = "jdbc:postgresql://localhost:5432/postgres";
    private final String user = "postgres";
    private final String password = "1";

    public DatabaseService() {
        try (Connection conn = getConnection()) {

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS users (
                    chat_id BIGINT PRIMARY KEY,
                    username TEXT,
                    firstname TEXT,
                    created_at TIMESTAMP DEFAULT NOW()
                );
            """);

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS topic (
                    id SERIAL PRIMARY KEY,
                    name TEXT UNIQUE
                );
            """);

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS question (
                    id SERIAL PRIMARY KEY,
                    topic_id INT REFERENCES topics(id) ON DELETE CASCADE,
                    text TEXT
                );
            """);

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS option (
                    id SERIAL PRIMARY KEY,
                    question_id INT REFERENCES questions(id) ON DELETE CASCADE,
                    text TEXT,
                    is_correct BOOLEAN
                );
            """);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    public void saveTestFromWord(AdvancedTelegramBot bot, String fileId, String topicName) throws Exception {

        File file = bot.downloadFile(bot.execute(new GetFile(fileId)));
        XWPFDocument doc = new XWPFDocument(new FileInputStream(file));

        List<XWPFParagraph> paragraphs = doc.getParagraphs();
        List<String> cleanedLines = new ArrayList<>();

        // Bo'sh bo'lmagan paragraf matnlarini yig'ish (trim qilib)
        for (XWPFParagraph p : paragraphs) {
            String text = p.getText().trim();
            if (!text.isBlank()) {
                cleanedLines.add(text);
            }
        }

        int topicId = saveTopic(topicName);

        int i = 0;
        while (i < cleanedLines.size()) {
            // Savol matnini yig'ish: "Savol:" bilan boshlanadigan qator(lar)
            if (!cleanedLines.get(i).startsWith("Savol:")) {
                throw new RuntimeException("❌ Savol 'Savol:' bilan boshlanmagan: " + cleanedLines.get(i));
            }

            StringBuilder questionBuilder = new StringBuilder();
            while (i < cleanedLines.size()) {
                String line = cleanedLines.get(i);

                // Agar line raqam bilan boshlansa (1. 2. 3. 4.) — variantlar boshlanmoqda
                if (line.matches("^\\s*[1-4]\\s*\\..*")) {
                    break;
                }

                // "Savol:" ni faqat birinchi marta olib tashlaymiz
                if (questionBuilder.length() == 0) {
                    questionBuilder.append(line.replace("Savol:", "").trim());
                } else {
                    questionBuilder.append(" ").append(line.trim());
                }
                i++;
            }

            String questionText = questionBuilder.toString().trim();
            if (questionText.isEmpty()) {
                throw new RuntimeException("❌ Savol matni bo'sh");
            }

            int questionId = saveQuestion(topicId, questionText);

            // Endi variantlarni o'qish (kamida 1 ta bo'lishi kerak)
            int optionsCount = 0;
            while (i < cleanedLines.size() && optionsCount < 4) {
                String opt = cleanedLines.get(i);

                // Variant raqam bilan boshlanishi kerak (1., 2., 3., 4.)
                if (!opt.matches("^\\s*[1-4]\\s*\\..*")) {
                    break; // variantlar tugadi
                }

                boolean correct = opt.contains("*");
                String cleanText = opt
                        .replace("*", "")
                        .replaceAll("^\\s*[1-4]\\s*\\.", "")
                        .trim();

                saveOption(questionId, cleanText, correct);
                optionsCount++;
                i++;
            }

            // Agar 4 ta variant yetarli bo'lmasa, ogohlantirish beramiz
            if (optionsCount < 4) {
                System.out.println("⚠️ Ogohlantirish: Savol uchun variantlar 4 taga yetmadi, faqat " + optionsCount + " ta saqlandi");
            }
        }

        // Faylni o'chirish (ixtiyoriy)
        file.delete();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    // ================= USERS =================

    public void saveUser(Long chatId, String username, String firstName) {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO users(chat_id, username, firstname)
                VALUES (?, ?, ?)
                ON CONFLICT (chat_id) DO NOTHING
            """);
            ps.setLong(1, chatId);
            ps.setString(2, username);
            ps.setString(3, firstName);
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ResultSet getAllUsers() {
        try {
            Connection conn = getConnection();
            return conn.createStatement()
                    .executeQuery("SELECT chat_id FROM users");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ================= TEST SYSTEM =================

    // 📌 MAVZU SAQLASH
    public int saveTopic(String name) throws SQLException {
        try (Connection conn = getConnection()) {

            PreparedStatement check = conn.prepareStatement(
                    "SELECT id FROM topic WHERE name = ?");
            check.setString(1, name);
            ResultSet rs = check.executeQuery();

            if (rs.next()) {
                return rs.getInt("id"); // agar oldin bor bo‘lsa
            }

            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO topic(name) VALUES (?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, name);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getInt(1);
        }
    }

    // 📌 SAVOL SAQLASH
    public int saveQuestion(int topicId, String text) throws SQLException {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO question(topic_id, text) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setInt(1, topicId);
            ps.setString(2, text);
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            rs.next();
            return rs.getInt(1);
        }
    }

    // 📌 VARIANT SAQLASH
    public void saveOption(int questionId, String text, boolean correct) throws SQLException {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO option(question_id, text, is_correct) VALUES (?, ?, ?)"
            );
            ps.setInt(1, questionId);
            ps.setString(2, text);
            ps.setBoolean(3, correct);
            ps.executeUpdate();
        }
    }
}
