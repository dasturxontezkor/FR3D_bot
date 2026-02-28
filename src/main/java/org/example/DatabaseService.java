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

    private final String url = "jdbc:postgresql://shortline.proxy.rlwy.net:15749/railway?sslmode=require";
    private final String user = "postgres";
    private final String password = "aakqPuooVQjZJsPBxeffcpMbEGPkNhaF";

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


    public String getLatestResultsReport(int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Latest Results (top ").append(limit).append(")\n\n");

        try (Connection conn = getConnection()) {

            PreparedStatement ps = conn.prepareStatement("""
            SELECT r.topic_name, r.correct_count, r.total_count, r.percent, r.created_at,
                   u.firstname, u.username
            FROM results r
            LEFT JOIN users u ON u.chat_id = r.user_chat_id
            ORDER BY r.created_at DESC
            LIMIT ?
        """);
            ps.setInt(1, limit);

            ResultSet rs = ps.executeQuery();

            int idx = 1;
            boolean any = false;

            while (rs.next()) {
                any = true;

                String firstName = rs.getString("firstname");
                String username = rs.getString("username");
                String who = (firstName != null && !firstName.isBlank())
                        ? firstName
                        : (username != null && !username.isBlank() ? "@" + username : "Unknown");

                String topic = rs.getString("topic_name");
                int correct = rs.getInt("correct_count");
                int total = rs.getInt("total_count");
                int percent = rs.getInt("percent");
                Timestamp time = rs.getTimestamp("created_at");

                sb.append(idx++).append(") ")
                        .append("👤 ").append(who).append("\n")
                        .append("📘 ").append(topic == null ? "-" : topic).append("\n")
                        .append("✅ ").append(correct).append("/").append(total)
                        .append(" (").append(percent).append("%)\n")
                        .append("🕒 ").append(time).append("\n\n");
            }

            if (!any) {
                return "📊 Hozircha natijalar yo‘q.";
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Natijalarni o‘qishda xatolik: " + e.getMessage();
        }

        // Telegram limit
        String out = sb.toString();
        if (out.length() > 3900) out = out.substring(0, 3900) + "\n\n...";
        return out;
    }



    public void saveTestFromWord(AdvancedTelegramBot bot, String fileId, String topicName) throws Exception {

        File file = bot.downloadFile(bot.execute(new GetFile(fileId)));

        // 1) Word'dan satrlarni o'qib olamiz (docx/doc)
        List<String> lines = readWordLines(file);

        // 2) Satrlardan bo'shlarni olib tashlab, trim qilamiz
        List<String> cleaned = new ArrayList<>();
        for (String l : lines) {
            if (l == null) continue;
            String t = l.trim();
            if (!t.isBlank()) cleaned.add(t);
        }

        if (cleaned.isEmpty()) {
            file.delete();
            throw new RuntimeException("❌ Word fayl bo‘sh yoki o‘qib bo‘lmadi.");
        }

        // 3) Topic saqlash
        int topicId = saveTopic(topicName);

        int i = 0;

        while (i < cleaned.size()) {

            // --- 4) Savolni yig'amiz (Savol: bo'lishi shart emas) ---
            StringBuilder q = new StringBuilder();

            String first = cleaned.get(i);

            // Savol raqamini olib tashlash: "1)", "1.", "1-" ...
            first = first.replaceAll("^\\s*\\d+\\s*[\\)\\.\\-]\\s*", "");

            // "Savol:" bo'lsa olib tashlaymiz
            if (first.startsWith("Savol:")) {
                first = first.substring("Savol:".length()).trim();
            }

            q.append(first);
            i++;

            // Savol bir nechta qatordan iborat bo'lishi mumkin.
            // Variantlar boshlanguncha (A:/B:/C:/D:) qo'shib boramiz
            while (i < cleaned.size() && !isOptionLine(cleaned.get(i))) {
                String extra = cleaned.get(i).trim();

                // Savol raqami yoki Savol: yana uchrasa ham olib tashlaymiz
                extra = extra.replaceAll("^\\s*\\d+\\s*[\\)\\.\\-]\\s*", "");
                if (extra.startsWith("Savol:")) extra = extra.substring("Savol:".length()).trim();

                if (!extra.isBlank()) {
                    q.append(" ").append(extra);
                }
                i++;
            }

            String questionText = q.toString().trim();
            if (questionText.isEmpty()) {
                throw new RuntimeException("❌ Savol matni bo‘sh topildi.");
            }

            // --- 5) Endi variantlar bo‘lishi shart ---
            if (i >= cleaned.size() || !isOptionLine(cleaned.get(i))) {
                throw new RuntimeException("❌ Variantlar topilmadi (A/B/C/D). Savol: " + questionText);
            }

            int questionId = saveQuestion(topicId, questionText);

            // Variantlar: A, B, C, D
            boolean gotA = false, gotB = false, gotC = false, gotD = false;
            int correctCount = 0;

            // 6) A/B/C/D variantlarini o'qib olamiz
            while (i < cleaned.size() && isOptionLine(cleaned.get(i))) {
                String optLine = cleaned.get(i).trim();

                // option letter
                String letter = optLine.substring(0, 1).toUpperCase(); // A/B/C/D

                boolean correct = optLine.contains("*");
                if (correct) correctCount++;

                // Matnni ajratib olamiz
                String optText = optLine
                        .replace("*", "")
                        .replaceFirst("^(A|B|C|D)\\s*:\\s*", "")
                        .trim();

                if (optText.isEmpty()) {
                    throw new RuntimeException("❌ Variant matni bo‘sh. Savol: " + questionText + " | Line: " + optLine);
                }

                saveOption(questionId, optText, correct);

                if (letter.equals("A")) gotA = true;
                if (letter.equals("B")) gotB = true;
                if (letter.equals("C")) gotC = true;
                if (letter.equals("D")) gotD = true;

                i++;

                // Agar A,B,C,D hammasi kiritilgan bo'lsa (ko'pincha 4 ta bo'ladi) to'xtatamiz
                if (gotA && gotB && gotC && gotD) break;
            }

            // 7) Tekshiruvlar (ixtiyoriy, lekin foydali)
            if (!(gotA && gotB && gotC && gotD)) {
                System.out.println("⚠️ Ogohlantirish: 4 ta variant to‘liq emas. Savol: " + questionText);
            }
            if (correctCount == 0) {
                System.out.println("⚠️ Ogohlantirish: To‘g‘ri javob (*) belgilanmagan. Savol: " + questionText);
            } else if (correctCount > 1) {
                System.out.println("⚠️ Ogohlantirish: Bir nechta to‘g‘ri javob (*) belgilangan. Savol: " + questionText);
            }

            // 8) keyingi savolga o'tadi (while davom etadi)
        }

        file.delete();
    }


    private List<String> readWordLines(File file) throws Exception {

        if (file == null || !file.exists()) {
            throw new RuntimeException("❌ Fayl topilmadi.");
        }

        List<String> rawLines = new ArrayList<>();

        // ✅ Faqat DOCX: extension tekshirmaymiz, o‘qib ko‘ramiz
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null) {
                    text = cleanText(text);
                    if (!text.isBlank()) rawLines.add(text);
                }
            }

        } catch (Exception e) {
            // agar docx bo‘lmasa shu yerda yiqiladi
            throw new RuntimeException("❌ Fayl .docx (Word) emas yoki buzilgan. Iltimos haqiqiy .docx yuboring.");
        }

        // A: B: C: D: bir qatorda yopishib kelsa ajratamiz
        List<String> normalized = new ArrayList<>();
        for (String line : rawLines) {
            normalized.addAll(splitInlineOptions(line));
        }

        return normalized;
    }

    private String cleanText(String text) {
        if (text == null) return "";
        text = text.replace("\r", " ");
        text = text.replace("\n", " ");
        text = text.replaceAll("\\s+", " ");
        return text.trim();
    }

    private List<String> splitInlineOptions(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.isBlank()) return result;

        // A: bor bo'lmasa - o'zini qaytar
        if (!line.matches(".*\\b[A-D]\\s*:\\s*.*")) {
            result.add(line.trim());
            return result;
        }

        // A:/B:/C:/D: bo'yicha bo'lib tashlaymiz
        String[] parts = line.split("(?=\\b[A-D]\\s*:)");

        for (String part : parts) {
            String t = part.trim();
            if (!t.isBlank()) result.add(t);
        }
        return result;
    }

    // ✅ Variant qatori ekanligini aniqlash (A:/B:/C:/D:)
    private boolean isOptionLine(String line) {
        if (line == null) return false;
        return line.trim().matches("^(A|B|C|D)\\s*:\\s*.*");
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
