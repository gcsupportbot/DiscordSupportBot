package github.gcsupportbot.discordsupportbot.listeners;

import github.gcsupportbot.discordsupportbot.DiscordSupportBot;
import github.gcsupportbot.discordsupportbot.GuildInfo;
import net.dv8tion.jda.core.entities.ISnowflake;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.MessageHistory;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import net.dv8tion.jda.core.requests.RestAction;
import org.apache.commons.lang.StringUtils;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public class DiscordSupportTicketCloseListener extends ListenerAdapter {

    @Override
    public void onGuildMessageReactionAdd(GuildMessageReactionAddEvent event) {
        Thread thread = new Thread(() -> handleTicketClose(event));
        thread.setName("Ticket closure thread - " + event.getChannel().getId() + " by " + event.getUser().getId());
        thread.setUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            t.interrupt();
        });
        thread.start();
    }

    private void handleTicketClose(GuildMessageReactionAddEvent event) {
        GuildInfo guildInfo = DiscordSupportBot.getGuildInfo(event.getGuild());
        if (guildInfo == null || !guildInfo.isSetUp()) return;
        String possibleTicketAuthorId = event.getChannel().getName().replace(guildInfo.getFirstMessageChannel().getName() + "-", "");
        if (!StringUtils.isNumeric(possibleTicketAuthorId)) return;
        User ticketAuthor = event.getJDA().getUserById(possibleTicketAuthorId);

        boolean allowedToClose = (guildInfo.isAuthorCanCloseTicket() && event.getUser().equals(ticketAuthor)) ||
                event.getMember().getRoles().stream().map(ISnowflake::getId).anyMatch(s -> guildInfo.getRolesAllowedToCloseTickets().contains(s));
        if (!allowedToClose) return;

        // event.getMessageId()
        try {
            MessageHistory history = event.getChannel().getHistory();
            List<Message> retrievedMessages = null;
            while (retrievedMessages == null || retrievedMessages.size() > 0) retrievedMessages = history.retrievePast(100).complete();
            Message firstMessage = history.getRetrievedHistory().get(history.getRetrievedHistory().size() - 1);
            if (!event.getMessageId().equals(firstMessage.getId())) return;
        } catch (Exception e) {
            e.printStackTrace();
        }

        event.getChannel().sendMessage("Ticket marked as solved by " + event.getUser().getAsMention() + "! Closing ticket " + (guildInfo.isPmTranscriptsOnClose() ? "and PMing the transcript to all participants " : "") + "in " + guildInfo.getSecondsUntilTicketCloses() + " seconds...").complete();

        try {
            Thread.sleep(guildInfo.getSecondsUntilTicketCloses() * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        MessageHistory history = event.getChannel().getHistory();
        while (history.retrievePast(100).complete().size() > 0);

        List<String> transcriptList = new LinkedList<>();
        for (Message message : history.getRetrievedHistory()) {
            String timeStamp = message.getCreationTime().getMonth().name() + " " + message.getCreationTime().getDayOfMonth() + ", " + message.getCreationTime().getYear() + " " + message.getCreationTime().getHour() + ":" + message.getCreationTime().getMinute() + ":" + message.getCreationTime().getSecond();
            transcriptList.add("[" + timeStamp + "] " + message.getAuthor() + ": " + message.getRawContent());
        }
        Collections.reverse(transcriptList);
        List<String> transcriptMessages = new LinkedList<>();
        List<String> builtMessageList = new LinkedList<>();
        for (String message : transcriptList) {
            if (builtMessageList.stream().mapToInt(String::length).sum() + builtMessageList.size() + message.length() + 1 > 1992) {
                transcriptMessages.add("```\n" + String.join("\n", builtMessageList) + "\n```");
                builtMessageList.clear();
            }
            builtMessageList.add(message.replace("```", "`"));
        }
        if (builtMessageList.size() > 0) transcriptMessages.add("```\n" + String.join("\n", builtMessageList) + "\n```");

        List<User> usersToMessageTranscriptTo = history.getRetrievedHistory().stream().map(Message::getAuthor).filter(user -> !user.isBot()).distinct().collect(Collectors.toList());
        usersToMessageTranscriptTo.stream().filter(user -> !user.isFake()).map(User::openPrivateChannel).map(RestAction::complete).forEach(privateChannel -> {
            privateChannel.sendMessage("Support ticket transcript regarding " + ticketAuthor + "'s ticket in " + event.getGuild()).queue();
            for (String transcriptMessage : transcriptMessags) {
                privateChannel.sendMessage(transcriptMessage).queue();
            }
        });

        event.getChannel().delete().queue();
    }

}
