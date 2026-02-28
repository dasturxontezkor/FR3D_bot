package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;

public class AdvancedTelegramBot extends TelegramLongPollingBot {

    private final DatabaseService db = new DatabaseService();
    private final AdminService adminService = new AdminService(db);
    public final Map<Long, Boolean> awaitingAdminPassword = new HashMap<>();

    // Majburiy kanallar ro‘yxati
    private final List<String> requiredChannels = List.of(
            "@iamfredo7"
    );

    @Override
    public String getBotUsername() {
        return "@imfredo_bot";
    }

    @Override
    public String getBotToken() {
        return "8004677699:AAHApsq0Mc3upa84LLZES-N7VGhz_MvrqhM";
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (adminService.handleAdminCommands(update, this)) return;

            if (update.hasMessage() && update.getMessage().hasText()) {
                Long chatId = update.getMessage().getChatId();
                String text = update.getMessage().getText();
                String username = update.getMessage().getFrom().getUserName();
                String firstName = update.getMessage().getFrom().getFirstName();

                db.saveUser(chatId, username, firstName);

                if (text.equals("/start")) {
                    Long userId = update.getMessage().getFrom().getId();

                    List<String> unsubscribed = getUnsubscribedChannels(userId);
                    if (unsubscribed.isEmpty()) {
                        sendAccessGranted(chatId);
                    } else {
                        sendStartWithInlineChannels(chatId);
                    }
                } else if (text.equals("\uD83E\uDDE0 Start Test")) {
                    sendWebApp(chatId);
                }
            }

            if (update.hasCallbackQuery()) {
                String data = update.getCallbackQuery().getData();

                Long chatId = update.getCallbackQuery().getMessage().getChatId();
                Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

                Long userId = update.getCallbackQuery().getFrom().getId(); // ✅ mana shuni qo‘shing

                if (data.equals("check_subs")) {
                    List<String> unsubscribed = getUnsubscribedChannels(userId); // ✅ userId
                    if (unsubscribed.isEmpty()) {
                        sendAccessGranted(chatId);
                    } else {
                        updateUnsubscribedChannels(chatId, messageId, unsubscribed);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<String> getUnsubscribedChannels(Long userId) {
        List<String> notJoined = new ArrayList<>();
        for (String channel : requiredChannels) {
            try {
                GetChatMember chatMember = new GetChatMember(channel, userId);
                ChatMember member = execute(chatMember);
                String status = member.getStatus();

                if ("left".equals(status) || "kicked".equals(status)) {
                    notJoined.add(channel);
                }
            } catch (Exception e) {
                notJoined.add(channel);
            }
        }
        return notJoined;
    }


    private void sendStartWithInlineChannels(Long chatId) throws Exception {
        String text = "💬 Hello! Welcome to our bot.\n" +
                "\n" +
                "✅ TESTS ARE WAITING FOR YOU!!!\n" +
                "\n" +
                "- Various quizzes\n" +
                "- Test your knowledge\n" +
                "- Gain new skills\n" +
                "- Compete with peers\n" +
                "\n" +
                "🔋 Please subscribe to our channels and group to start the test.";

        Map<String, String> channelNamesMap = new HashMap<>();
        channelNamesMap.put("iamfredo7", "CHANNEL");

        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        for (String ch : requiredChannels) {
            String displayName = channelNamesMap.getOrDefault(ch, ch);
            InlineKeyboardButton btn = new InlineKeyboardButton("📢 " + displayName);
            btn.setUrl("https://t.me/" + ch.substring(1));
            buttons.add(List.of(btn));
        }

        InlineKeyboardButton checkBtn = new InlineKeyboardButton("✅ Check Subscription");
        checkBtn.setCallbackData("check_subs");
        buttons.add(List.of(checkBtn));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(buttons);

        SendMessage msg = new SendMessage(chatId.toString(), text);
        msg.setReplyMarkup(markup);
        execute(msg);
    }

    private void updateUnsubscribedChannels(Long chatId, Integer messageId, List<String> unsubscribed) {
        try {
            StringBuilder sb = new StringBuilder("🙃 You haven't subscribed to the channel or group!\n\n");
            sb.append("🎯 Please subscribe using the buttons below and try again:\n\n");

            Map<String, String> channelNamesMap = new HashMap<>();
            channelNamesMap.put("@iamfredo7", "iamfredo");

            List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

            for (String ch : unsubscribed) {
                String displayName = channelNamesMap.getOrDefault(ch, ch);
                sb.append("👉 ").append(displayName).append("\n");

                InlineKeyboardButton btn = new InlineKeyboardButton("📢 " + displayName);
                btn.setUrl("https://t.me/" + ch.substring(1));
                buttons.add(List.of(btn));
            }

            sb.append("\n🔄 After subscribing, check again:");

            InlineKeyboardButton checkBtn = new InlineKeyboardButton("✅ Check Again");
            checkBtn.setCallbackData("check_subs");
            buttons.add(List.of(checkBtn));

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(buttons);

            EditMessageText editMsg = new EditMessageText();
            editMsg.setChatId(chatId.toString());
            editMsg.setMessageId(messageId);
            editMsg.setText(sb.toString());
            editMsg.setReplyMarkup(markup);

            try {
                execute(editMsg);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                    System.out.println("⚠️ Message not modified.");
                } else {
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendAccessGranted(Long chatId) throws Exception {
        SendMessage msg = new SendMessage(chatId.toString(),
                "🎉 Awesome! You have subscribed to all channels.\n" +
                        "Now you can start the test 👇");

        KeyboardButton testBtn = new KeyboardButton("🧠 Start Test");
        KeyboardRow row = new KeyboardRow();
        row.add(testBtn);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(List.of(row));
        markup.setResizeKeyboard(true);
        msg.setReplyMarkup(markup);

        execute(msg);
    }

    private void sendWebApp(Long chatId) throws Exception {
        SendMessage msg = new SendMessage(chatId.toString(), "🌐 Press the button below to start the test:");
        InlineKeyboardButton webButton = new InlineKeyboardButton("🚀 Start Test");
        webButton.setWebApp(new WebAppInfo("https://fred-website-ashy.vercel.app/"));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(webButton)));
        msg.setReplyMarkup(markup);
        execute(msg);
    }

    public void executeSafely(SendMessage message) {
        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void executeSafely(SendPhoto photo) {
        try {
            execute(photo);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void showAdminMenu(Long chatId) {
        KeyboardButton sendMsgBtn = new KeyboardButton("📢 Send Message");
        KeyboardRow row = new KeyboardRow();
        row.add(sendMsgBtn);
        KeyboardButton addButton = new KeyboardButton("➕ Add Test");
        KeyboardRow row2 = new KeyboardRow();
        row2.add(addButton);

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(List.of(row,row2));
        markup.setResizeKeyboard(true);

        SendMessage msg = new SendMessage(chatId.toString(), "🔐 Admin Menu:");
        msg.setReplyMarkup(markup);
        executeSafely(msg);
    }

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new AdvancedTelegramBot());
            System.out.println("✅ Bot started!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
