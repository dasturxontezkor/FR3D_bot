package org.example;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.sql.ResultSet;
import java.util.*;

public class AdminService {

    private final DatabaseService db;

    // ✅ ADMIN sozlamalar
    private static final String ADMIN_PASSWORD = "secret123";
    private static final Long ADMIN_ID = 679730369L; // <-- sizning USER ID

    // ✅ Tugmalar (showAdminMenu bilan 100% bir xil bo‘lsin!)
    private static final String BTN_SEND_MESSAGE = "📢 Send Message";
    private static final String BTN_ADD_TEST = "➕ Add Test";
    private static final String BTN_SHOW_RESULTS = "📊 Show Results"; // ✅ qo‘shildi

    // ✅ Callback data
    private static final String CB_BROADCAST_TEXT = "broadcast_text";
    private static final String CB_BROADCAST_PHOTO = "broadcast_photo";

    // ✅ State'lar
    private final Map<Long, Boolean> awaitingBroadcastText = new HashMap<>();
    private final Map<Long, Boolean> awaitingPhoto = new HashMap<>();
    private final Map<Long, String> pendingPhotoFileId = new HashMap<>();

    private final Map<Long, Boolean> awaitingTopicName = new HashMap<>();
    private final Map<Long, Boolean> awaitingWordFile = new HashMap<>();
    private final Map<Long, String> tempTopicName = new HashMap<>();

    public AdminService(DatabaseService db) {
        this.db = db;
    }

    public boolean handleAdminCommands(Update update, AdvancedTelegramBot bot) {
        try {
            if (update == null) return false;

            Long chatId = getChatId(update);
            if (chatId == null) return false;

            Long userId = getUserId(update); // ✅ admin tekshiruvi uchun
            String text = getText(update);

            // ✅ /cancel - har qanday jarayonni bekor qilish
            if ("/cancel".equalsIgnoreCase(text)) {
                clearAllStates(chatId, bot);
                bot.executeSafely(new SendMessage(chatId.toString(), "✅ Bekor qilindi."));
                if (isAdmin(userId)) bot.showAdminMenu(chatId);
                return true;
            }

            // ✅ 1) Admin rejimga kirish
            if ("/entertoadmin".equals(text)) {
                bot.awaitingAdminPassword.put(chatId, true);
                bot.executeSafely(new SendMessage(chatId.toString(), "🔑 Parolni kiriting:"));
                return true;
            }

            // ✅ 2) Parol kiritilayotgan bo'lsa
            if (bot.awaitingAdminPassword.getOrDefault(chatId, false)) {
                if (ADMIN_PASSWORD.equals(text)) {
                    bot.awaitingAdminPassword.put(chatId, false);

                    if (isAdmin(userId)) {
                        bot.executeSafely(new SendMessage(chatId.toString(),
                                "✅ Xush kelibsiz, admin!\n\nBekor qilish: /cancel"));
                        bot.showAdminMenu(chatId);
                    } else {
                        bot.executeSafely(new SendMessage(chatId.toString(),
                                "❌ Siz admin emassiz (ID mos emas)."));
                    }
                } else {
                    bot.executeSafely(new SendMessage(chatId.toString(), "❌ Noto‘g‘ri parol!"));
                }
                return true;
            }

            // 🔒 Admin bo‘lmasa, qolgan admin funksiyalar ishlamasin
            if (!isAdmin(userId)) return false;

            // ✅ NEW: Show Results
            if (BTN_SHOW_RESULTS.equals(text)) {
                bot.sendLatestResults(chatId, 20);
                return true;
            }

            // ✅ 3) Add Test bosildi
            if (BTN_ADD_TEST.equals(text)) {
                clearAllStates(chatId, bot);
                awaitingTopicName.put(chatId, true);
                bot.executeSafely(new SendMessage(chatId.toString(),
                        "✏️ Mavzu nomini kiriting:\n\nBekor qilish: /cancel"));
                return true;
            }

            // ✅ 4) Topic name kutilayotgan bo'lsa
            if (awaitingTopicName.getOrDefault(chatId, false)) {
                if (text == null || text.isBlank()) {
                    bot.executeSafely(new SendMessage(chatId.toString(),
                            "❗️Mavzu nomi bo‘sh bo‘lmasin. Qaytadan kiriting:\nBekor qilish: /cancel"));
                    return true;
                }

                tempTopicName.put(chatId, text.trim());
                awaitingTopicName.put(chatId, false);
                awaitingWordFile.put(chatId, true);

                bot.executeSafely(new SendMessage(chatId.toString(),
                        "📄 Word (.docx) faylni yuboring:\n\n" +
                                "Format:\n" +
                                "Savol: (yoki Savolsiz ham bo‘ladi)\n" +
                                "A:\nB:\nC:\nD: *\n\n" +
                                "⚠️ To‘g‘ri javob oxiriga * qo‘ying\n" +
                                "Bekor qilish: /cancel"));
                return true;
            }

            // ✅ 5) Word fayl kutilayotgan bo'lsa
            if (awaitingWordFile.getOrDefault(chatId, false)
                    && update.hasMessage()
                    && update.getMessage().hasDocument()) {

                String fileName = update.getMessage().getDocument().getFileName();
                if (fileName == null || !fileName.toLowerCase().endsWith(".docx")) {
                    bot.executeSafely(new SendMessage(chatId.toString(),
                            "❌ Faqat .docx fayl yuboring.\nBekor qilish: /cancel"));
                    return true;
                }

                String fileId = update.getMessage().getDocument().getFileId();
                String topicName = tempTopicName.get(chatId);

                db.saveTestFromWord(bot, fileId, topicName);

                bot.executeSafely(new SendMessage(chatId.toString(), "✅ Test muvaffaqiyatli qo‘shildi!"));

                awaitingWordFile.remove(chatId);
                tempTopicName.remove(chatId);

                bot.showAdminMenu(chatId);
                return true;
            }

            // ✅ 6) Broadcast menyu (Send Message)
            if (BTN_SEND_MESSAGE.equals(text)) {
                clearBroadcastStates(chatId);
                SendMessage msg = new SendMessage(chatId.toString(),
                        "📝 Yuboriladigan xabar turini tanlang:\n\nBekor qilish: /cancel");
                msg.setReplyMarkup(getBroadcastTypeButtons());
                bot.executeSafely(msg);
                return true;
            }

            // ✅ 7) Callback (matn/rasm tanlash)
            if (update.hasCallbackQuery()) {
                String data = update.getCallbackQuery().getData();

                if (CB_BROADCAST_TEXT.equals(data)) {
                    clearBroadcastStates(chatId);
                    awaitingBroadcastText.put(chatId, true);
                    bot.executeSafely(new SendMessage(chatId.toString(),
                            "✏️ Matnni kiriting:\n\nBekor qilish: /cancel"));
                    return true;
                }

                if (CB_BROADCAST_PHOTO.equals(data)) {
                    clearBroadcastStates(chatId);
                    awaitingPhoto.put(chatId, true);
                    bot.executeSafely(new SendMessage(chatId.toString(),
                            "🖼 Rasmni yuboring:\n\nBekor qilish: /cancel"));
                    return true;
                }
            }

            // ✅ 8) Photo kutilayotgan bo'lsa
            if (awaitingPhoto.getOrDefault(chatId, false)
                    && update.hasMessage()
                    && update.getMessage().hasPhoto()) {

                List<PhotoSize> photos = update.getMessage().getPhoto();
                if (photos != null && !photos.isEmpty()) {
                    String photoId = photos.get(photos.size() - 1).getFileId();
                    pendingPhotoFileId.put(chatId, photoId);

                    awaitingPhoto.put(chatId, false);
                    awaitingBroadcastText.put(chatId, true);

                    bot.executeSafely(new SendMessage(chatId.toString(),
                            "📝 Endi caption (yozuv)ni kiriting:\n\nBekor qilish: /cancel"));
                }
                return true;
            }

            // ✅ 9) Broadcast matn
            if (awaitingBroadcastText.getOrDefault(chatId, false)
                    && text != null
                    && !text.isBlank()
                    && !text.startsWith("/")) {

                String photoId = pendingPhotoFileId.get(chatId);

                ResultSet rs = db.getAllUsers();
                int sentCount = 0;

                while (rs != null && rs.next()) {
                    Long userChatId = rs.getLong("chat_id");

                    try {
                        if (photoId != null) {
                            SendPhoto photo = new SendPhoto();
                            photo.setChatId(userChatId.toString());
                            photo.setPhoto(new InputFile(photoId));
                            photo.setCaption("📢 Admin xabari:\n\n" + text);
                            bot.execute(photo);
                        } else {
                            SendMessage msg = new SendMessage(userChatId.toString(),
                                    "📢 Admin xabari:\n\n" + text);
                            bot.execute(msg);
                        }
                        sentCount++;

                    } catch (TelegramApiRequestException e) {
                        String m = (e.getMessage() == null) ? "" : e.getMessage().toLowerCase();
                        if (m.contains("403") || m.contains("bot was blocked by the user") || m.contains("forbidden")) {
                            System.out.println("⚠️ User botni bloklagan: " + userChatId);
                        } else {
                            System.out.println("⚠️ Yuborishda xatolik (user=" + userChatId + "): " + e.getMessage());
                        }
                    } catch (Exception e) {
                        System.out.println("❌ Xabar yuborishda xatolik: " + e.getMessage());
                    }
                }

                bot.executeSafely(new SendMessage(chatId.toString(),
                        "✅ Xabar " + sentCount + " ta foydalanuvchiga yuborildi."));

                clearBroadcastStates(chatId);
                bot.showAdminMenu(chatId);
                return true;
            }

            return false;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }




    private InlineKeyboardMarkup getBroadcastTypeButtons() {
        InlineKeyboardButton textBtn = new InlineKeyboardButton("💬 Faqat matn");
        textBtn.setCallbackData(CB_BROADCAST_TEXT);

        InlineKeyboardButton photoBtn = new InlineKeyboardButton("🖼 Rasm bilan");
        photoBtn.setCallbackData(CB_BROADCAST_PHOTO);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(Arrays.asList(textBtn, photoBtn));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private void clearBroadcastStates(Long chatId) {
        awaitingBroadcastText.remove(chatId);
        awaitingPhoto.remove(chatId);
        pendingPhotoFileId.remove(chatId);
    }

    private void clearAllStates(Long chatId, AdvancedTelegramBot bot) {
        clearBroadcastStates(chatId);
        awaitingTopicName.remove(chatId);
        awaitingWordFile.remove(chatId);
        tempTopicName.remove(chatId);
        bot.awaitingAdminPassword.remove(chatId);
    }

    private boolean isAdmin(Long userId) {
        return userId != null && userId.equals(ADMIN_ID);
    }

    private Long getChatId(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery()) return update.getCallbackQuery().getMessage().getChatId();
        return null;
    }

    private Long getUserId(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getId();
        }
        return null;
    }

    private String getText(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) return update.getMessage().getText();
        return "";
    }
}