/*
 * Copyright 2018 Duncan Casteleyn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package be.duncanc.discordmodbot.bot.services

import be.duncanc.discordmodbot.bot.commands.CommandModule
import be.duncanc.discordmodbot.bot.sequences.MessageSequence
import be.duncanc.discordmodbot.bot.sequences.ReactionSequence
import be.duncanc.discordmodbot.bot.sequences.Sequence
import be.duncanc.discordmodbot.bot.utils.limitLessBulkDeleteByIds
import be.duncanc.discordmodbot.data.entities.WelcomeMessage
import be.duncanc.discordmodbot.data.redis.hash.MemberGateQuestion
import be.duncanc.discordmodbot.data.repositories.key.value.MemberGateQuestionRepository
import be.duncanc.discordmodbot.data.services.MemberGateService
import be.duncanc.discordmodbot.data.services.WelcomeMessageService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Created by Duncan on 30/04/2017.
 * <p>
 * Welcomes users when the join, get accepted and makes them answer questions before they get accepted.
 */
@Component
@Transactional
class MemberGate(
    private val memberGateQuestionRepository: MemberGateQuestionRepository,
    private val memberGateService: MemberGateService,
    private val welcomeMessageService: WelcomeMessageService
) : CommandModule(
    arrayOf("gateConfig", "join", "review"),
    null,
    null,
    ignoreWhitelist = true
) {
    companion object {
        private const val MENTION_CHANNEL_TO_SET = "Please mention the channel you want to set."
        private const val NEED_TO_MENTION_CHANNEL = "A channel needs to be mentioned."
        private const val CHANNEL_SET = "Channel set"

        private val random = SecureRandom()
    }

    private val informUserMessageIds = HashMap<Long, Long>()

    /**
     * Check if a user was added to the approved role.
     */
    override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent) {
        val guild = event.guild
        val welcomeMessages = ArrayList(welcomeMessageService.getWelcomeMessages(guild.idLong))
        val memberRole = memberGateService.getMemberRole(guild.idLong, guild.jda)
        if (welcomeMessages.isEmpty() || event.user.isBot || memberRole !in event.roles) {
            return
        }
        val welcomeMessage = welcomeMessages[random.nextInt(welcomeMessages.size)].getWelcomeMessage(event.user)
        memberGateService.getWelcomeChannel(guild.idLong, guild.jda)?.sendMessage(welcomeMessage)?.queue()
        memberGateQuestionRepository.deleteById(event.user.idLong)
        memberGateService.getGateChannel(guild.idLong, guild.jda)?.let { cleanMessagesFromUser(it, event.user) }
    }

    /**
     * Cleans the messages from the users and messages containing mentions to the users from the member gate channel.
     *
     * If the channel contains more than 1000 message all messages past 1000 will be ignored.
     */
    private fun cleanMessagesFromUser(gateTextChannel: TextChannel, user: User) {
        if (!gateTextChannel.guild.selfMember.hasPermission(
                gateTextChannel,
                Permission.MESSAGE_MANAGE,
                Permission.MESSAGE_HISTORY
            )
        ) {
            return
        }
        gateTextChannel.history.size()
        val userMessages: ArrayList<Long> = ArrayList()
        gateTextChannel.iterableHistory
            .takeAsync(1000)
            .thenApply { messages ->
                messages.forEach {
                    if (it.author == user || it.contentRaw.contains(user.id)) {
                        userMessages.add(it.idLong)
                    }
                }
                true
            }.thenRun {
                gateTextChannel.limitLessBulkDeleteByIds(userMessages)
            }
    }

    /**
     * Welcomes a new member that joins and informs them about the member gate system.
     */
    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        if (event.user.isBot) {
            return
        }
        val gateTextChannel = memberGateService.getGateChannel(event.guild.idLong, event.jda)
        val welcomeChannel = memberGateService.getWelcomeChannel(event.guild.idLong, event.jda)
        if (gateTextChannel != null) {
            val memberGateQuestion = memberGateQuestionRepository.findById(event.user.idLong).orElse(null)
            if (memberGateQuestion != null) {
                gateTextChannel.sendMessage(
                    """
                        ${event.member.asMention} Welcome back. We stored your last answer for you.
                        A moderator can review it using `!review ${memberGateQuestion.id}`
                        """.trimIndent()
                ).queue { message ->
                    message.addReaction("❔").queue()
                    synchronized(informUserMessageIds) {
                        informUserMessageIds[memberGateQuestion.id] = message.idLong
                    }
                }
            } else {
                gateTextChannel.sendMessage(
                    "Welcome " + event.member.asMention + ", this server requires you to read the " +
                            (memberGateService.getRulesChannel(event.guild.idLong, event.jda)?.asMention
                                ?: "rules") +
                            " and answer a question regarding those before you gain full access.\n\n" +
                            "If you have read the rules and are ready to answer the question, type ``!" + super.aliases[1] + "`` and follow the instructions from the bot.\n\n" +
                            "Please read the pinned message for more information.\n" +
                            "Never ping moderators unless you have issues which prevent you from completing the entry process."
                ).queue { message -> message.delete().queueAfter(5, TimeUnit.MINUTES) }
            }
        } else if (welcomeChannel != null) {
            val welcomeMessages = welcomeMessageService.getWelcomeMessages(event.guild.idLong).toTypedArray()
            if (welcomeMessages.isNotEmpty()) {
                val welcomeMessage =
                    welcomeMessages[random.nextInt(welcomeMessages.size)].getWelcomeMessage(event.user)
                memberGateService.getWelcomeChannel(event.guild.idLong, event.jda)?.sendMessage(welcomeMessage)?.queue()
            }
        }
    }

    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        val gateChannel = memberGateService.getGateChannel(event.guild.idLong, event.jda)
        if (gateChannel == null || event.member?.roles?.contains(
                memberGateService.getMemberRole(
                    event.guild.idLong,
                    event.jda
                )
            ) == true
        ) {
            return
        }
        val userId = event.user.idLong
        synchronized(informUserMessageIds) {
            val messageToRemove = informUserMessageIds.remove(userId)
            if (messageToRemove != null) {
                gateChannel.retrieveMessageById(messageToRemove).queue { it.delete().queue() }
            }
        }
        cleanMessagesFromUser(gateChannel, event.user)

    }

    override fun commandExec(event: MessageReceivedEvent, command: String, arguments: String?) {
        if (!event.isFromType(ChannelType.TEXT)) {
            throw IllegalStateException("This command cannot be executed outside a text channel.")
        }
        if (/* !guildMemberGate.isPresent && !command.equals(super.aliases[0], true) || */ event.author.isBot) {
            return
        }

        when (command.lowercase(Locale.getDefault())) {
            super.aliases[0].lowercase(Locale.getDefault()) -> {
                configure(event)
            }

            super.aliases[1].lowercase(Locale.getDefault()) -> {
                join(event)
            }
            super.aliases[2].lowercase(Locale.getDefault()) -> {
                arguments?.let { review(event.jda, event.author, event.guild, event.channel, it) }
            }
        }
    }

    override fun onGuildMessageReactionAdd(event: GuildMessageReactionAddEvent) {
        if (
            event.reactionEmote.name != "❔" ||
            event.user == event.jda.selfUser ||
            event.guild.getMember(event.user)?.hasPermission(Permission.MANAGE_ROLES) != true ||
            memberGateService.getGateChannel(event.guild.idLong, event.jda) != event.channel
        ) {
            return
        }
        val message = event.retrieveMessage().submit().get(1, TimeUnit.MINUTES)
        if (message.author != event.jda.selfUser || !message.contentRaw.contains("!review") || message.mentionedMembers.size != 1) {
            return
        }
        val id = message.contentRaw.split(" ")[0].trimStart('<', '@', '!').trimEnd('>')
        review(event.jda, event.user, event.guild, event.channel, id)
    }

    private fun review(jda: JDA, author: User, guild: Guild, channel: MessageChannel, arguments: String) {
        if (guild.getMember(author)?.hasPermission(Permission.MANAGE_ROLES) == true) {
            jda.addEventListener(ReviewSequence(author, channel, arguments.toLong()))
        }
    }

    private fun join(event: MessageReceivedEvent) {
        val memberRole = memberGateService.getMemberRole(event.guild.idLong, event.jda)
        val member = event.guild.getMember(event.author)
        if (memberRole == null || member == null || member.roles.any { it.idLong == memberRole.idLong }) {
            return
        }

        if (memberGateQuestionRepository.existsById(member.idLong)) {
            event.channel.sendMessage("You have already tried answering a question. A moderator now needs to manually review you. Please be patient.")
                .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
            return
        }
        val questions = memberGateService.getQuestions(event.guild.idLong).toList()
        if (questions.isEmpty()) {
            accept(member)
        } else {
            event.jda.addEventListener(
                QuestionSequence(
                    event.author,
                    event.channel,
                    questions[random.nextInt(questions.size)]
                )
            )
        }
    }

    private fun configure(event: MessageReceivedEvent) {
        if (event.guild.getMember(event.author)?.hasPermission(Permission.MANAGE_ROLES) == true) {
            event.jda.addEventListener(ConfigureSequence(event.author, event.channel))
        }
    }

    /**
     * Grants a user access to the accepted role.
     */
    private fun accept(member: Member) {
        val guild = member.guild
        memberGateService.getMemberRole(guild.idLong, member.jda)
            ?.let { guild.addRoleToMember(member, it).queue() }
    }

    /**
     * Starts the manual review procedure.
     */
    private fun informMember(member: Member, question: String, answer: String, textChannel: TextChannel) {
        textChannel.sendMessage(
            member.asMention + " Please wait while a moderator manually checks your answer. You might be asked (an) other question(s).\n\n" +
                    "A moderator can use `!" + super.aliases[2] + " " + member.user.idLong + "`"
        ).queue {
            it.addReaction("❔").queue()
            synchronized(informUserMessageIds) {
                informUserMessageIds[member.user.idLong] = it.idLong
            }
        }
        val memberGateQuestion = MemberGateQuestion(member.user.idLong, question, answer)
        memberGateQuestionRepository.save(memberGateQuestion)
    }

    /**
     * This sequences questions a user.
     */
    private inner class QuestionSequence(
        user: User,
        channel: MessageChannel,
        private val question: String
    ) : Sequence(user, channel, informUser = false), MessageSequence {
        private var sequenceNumber: Byte = 0

        /**
         * Asks first question.
         */
        init {
            super.channel.sendMessage("${user.asMention} Have you read the rules? answer with `yes` or `no`")
                .queue { super.addMessageToCleaner(it) }
        }

        /**
         * Logic to check answers
         */
        override fun onMessageReceivedDuringSequence(event: MessageReceivedEvent) {
            if (super.user != event.author || super.channel != event.channel) {
                return
            }
            when (sequenceNumber) {
                0.toByte() -> {
                    when (event.message.contentRaw.lowercase(Locale.getDefault())) {
                        "yes" -> {
                            super.channel.sendMessage("${user.asMention} Do you accept the rules? Answer with `yes` or `no`")
                                .queue { super.addMessageToCleaner(it) }
                            sequenceNumber = 1
                        }
                        "no" -> {
                            destroy()
                            super.channel.sendMessage("${user.asMention} Please read the rules, before using this command.")
                                .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                        }
                        else -> {
                            super.channel.sendMessage("${user.asMention} Invalid response! Answer with `yes` or `no`!")
                                .queue { super.addMessageToCleaner(it) }
                        }
                    }
                }
                1.toByte() -> {
                    when (event.message.contentRaw.lowercase(Locale.getDefault())) {
                        "yes" -> {
                            super.channel.sendMessage(user.asMention + " Please answer the following question:\n" + question)
                                .queue { super.addMessageToCleaner(it) }
                            sequenceNumber = 2
                        }
                        "no" -> {
                            destroy()
                            val reason = "Doesn't agree with the rules."
                            event.guild.kick(event.member!!, reason).queue()
                            val logToChannel = event.jda.registeredListeners.firstOrNull { it is GuildLogger }
                            if (logToChannel != null) {
                                logToChannel as GuildLogger
                                logToChannel.logKick(
                                    event.author,
                                    event.guild,
                                    event.guild.getMember(event.jda.selfUser),
                                    reason
                                )
                            }
                        }
                        else -> {
                            super.channel.sendMessage(user.asMention + " Invalid response! Answer with \"yes\" or \"no\"!")
                                .queue { super.addMessageToCleaner(it) }
                        }
                    }
                }
                else -> {
                    destroy()
                    val member = event.guild.getMemberById(user.idLong)!!
                    informMember(
                        member = member,
                        question = question,
                        answer = event.message.contentDisplay,
                        textChannel = event.textChannel
                    )
                }
            }
        }
    }

    /**
     * This sequences allows to configure the gate
     */
    private inner class ConfigureSequence(user: User, channel: MessageChannel) :
        Sequence(user, channel), MessageSequence {
        private var sequenceNumber: Byte = 0
        private lateinit var questions: List<String>
        private lateinit var welcomeMessages: List<WelcomeMessage>
        private lateinit var welcomeMessage: WelcomeMessage

        /**
         * Asks first question
         */
        init {
            channel.sendMessage(
                user.asMention + " Welcome to the member gate configuration sequences.\n\n" +
                        "Select an action to perform:\n" +
                        "0. add a question\n" +
                        "1. remove a question\n" +
                        "2. Add welcome message\n" +
                        "3. Remove welcome message\n" +
                        "4. Change welcome channel\n" +
                        "5. Change member gate chanel\n" +
                        "6. Change member role\n" +
                        "7. Change rules channel\n" +
                        "8. Disable member approval gate (wipes your questions, member role, gate channel and rule channel settings)\n" +
                        "9. Disable welcome messages (wipes your welcomes message and channel settings)\n" +
                        "10. Wipe member gate module settings\n" +
                        "11. Set auto purge time in hours (purges members that don't complete entry process)\n" +
                        "12. Disable auto purge\n" +
                        "13. Set entry reminder time in hours (reminds people they will be purged)\n" +
                        "14. Disable entry reminder\n" +
                        "\nTo enable the member gate you need to set at least the member gate channel and the member role\n" +
                        "To enable welcome messages you need to set at least a welcome message and the welcome channel"
            ).queue { super.addMessageToCleaner(it) }
        }

        /**
         * Logic to handle configuration.
         */
        override fun onMessageReceivedDuringSequence(event: MessageReceivedEvent) {
            if (super.user != user || super.channel != channel) {
                return
            }
            val messageContent: String = event.message.contentDisplay.lowercase(Locale.getDefault())
            when (sequenceNumber) {
                0.toByte() -> {
                    findDesiredAction(messageContent, event)
                }
                1.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.addQuestion(guildId, event.message.contentRaw)
                    channel.sendMessage(super.user.asMention + " Question added.")
                        .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                    destroy()
                }
                2.toByte() -> {
                    val number: Int = event.message.contentDisplay.toInt()
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.removeQuestion(guildId, questions[number])
                    channel.sendMessage("The question \"" + questions[number] + "\" was removed.")
                        .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                    destroy()
                }
                3.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    welcomeMessage = WelcomeMessage(guildId = guildId, imageUrl = event.message.contentRaw)
                    channel.sendMessage("Please enter a welcome message.").queue { addMessageToCleaner(it) }
                    sequenceNumber = 4
                }
                4.toByte() -> {
                    welcomeMessage = welcomeMessage.copy(message = event.message.contentRaw)
                    welcomeMessageService.addWelcomeMessage(welcomeMessage)
                    channel.sendMessage("The new welcome message has been added.")
                        .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                    destroy()
                }
                5.toByte() -> {
                    val welcomeMessage = welcomeMessages[event.message.contentRaw.toInt()]
                    welcomeMessageService.removeWelcomeMessage(welcomeMessage)
                    channel.sendMessage("\"$welcomeMessage\" has been deleted.")
                        .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                    destroy()
                }
                6.toByte() -> {
                    val mentionedChannels = event.message.getMentions(Message.MentionType.CHANNEL)
                    if (mentionedChannels.isNotEmpty()) {
                        val guildId = (channel as TextChannel).guild.idLong
                        memberGateService.setWelcomeChannel(guildId, mentionedChannels[0] as TextChannel)
                        channel.sendMessage(CHANNEL_SET).queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                        destroy()
                    } else {
                        throw IllegalArgumentException(NEED_TO_MENTION_CHANNEL)
                    }
                }
                7.toByte() -> {
                    val mentionedChannels = event.message.getMentions(Message.MentionType.CHANNEL)
                    if (mentionedChannels.isNotEmpty()) {
                        val guildId = (channel as TextChannel).guild.idLong
                        memberGateService.setGateChannel(guildId, mentionedChannels[0] as TextChannel)
                        channel.sendMessage(CHANNEL_SET).queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                        destroy()
                    } else {
                        throw IllegalArgumentException(NEED_TO_MENTION_CHANNEL)
                    }
                }
                8.toByte() -> {
                    val targetRoles = event.guild.getRolesByName(event.message.contentRaw, true)
                    when {
                        targetRoles.size == 1 -> {
                            val guildId = (channel as TextChannel).guild.idLong
                            memberGateService.setMemberRole(guildId, targetRoles[0])
                            channel.sendMessage("Role set").queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                            destroy()
                        }
                        targetRoles.isEmpty() -> throw IllegalArgumentException("Couldn't find any roles with that name.")
                        else -> throw IllegalArgumentException("More then 1 match, please rename the role temporary.")
                    }

                }
                9.toByte() -> {
                    val mentionedChannels = event.message.getMentions(Message.MentionType.CHANNEL)
                    if (mentionedChannels.isNotEmpty()) {
                        val guildId = (channel as TextChannel).guild.idLong
                        memberGateService.setRulesChannel(guildId, mentionedChannels[0] as TextChannel)
                        channel.sendMessage("Channel set").queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                        destroy()
                    } else {
                        throw IllegalArgumentException("A channel needs to be mentioned.")
                    }
                }
                10.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.setPurgeTime(guildId, event.message.contentRaw.toLong())
                    channel.sendMessage("Purge time set").queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                    destroy()
                }
                11.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.setReminderTime(guildId, event.message.contentRaw.toLong())
                    channel.sendMessage("Reminder time set").queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
                    destroy()
                }
            }
        }

        private fun findDesiredAction(
            messageContent: String,
            event: MessageReceivedEvent
        ) {
            when (messageContent.toByte()) {
                0.toByte() -> {
                    sequenceNumber = 1
                    val addAQuestionMessage: MessageBuilder = MessageBuilder()
                        .append("Please send the question to add:\n")
                    super.channel.sendMessage(addAQuestionMessage.build())
                        .queue { super.addMessageToCleaner(it) }
                }
                1.toByte() -> {
                    sequenceNumber = 2
                    val questionListMessage = MessageBuilder()
                    val guildId = (channel as TextChannel).guild.idLong
                    questions = memberGateService.getQuestions(guildId).toList()
                    for (i in questions.indices) {
                        questionListMessage.append(i.toString()).append(". ").append(questions[i]).append('\n')
                    }
                    questionListMessage.append('\n').append("Respond with the question number to remove it.")
                    channel.sendMessage(questionListMessage.build()).queue { super.addMessageToCleaner(it) }
                }
                2.toByte() -> {
                    sequenceNumber = 3
                    channel.sendMessage("Please send a url (that will stay online) to an image to be used as welcome image.")
                        .queue { addMessageToCleaner(it) }
                }
                3.toByte() -> {
                    sequenceNumber = 5
                    val welcomeMessageList = MessageBuilder()
                    val guildId = (channel as TextChannel).guild.idLong
                    welcomeMessages = ArrayList(welcomeMessageService.getWelcomeMessages(guildId))
                    for (i in welcomeMessages.indices) {
                        welcomeMessageList.append(i.toString()).append(". ").append(welcomeMessages[i])
                            .append('\n')
                    }
                    welcomeMessageList.append('\n')
                        .append("Respond with the welcome message number to remove it.")
                    welcomeMessageList.buildAll(MessageBuilder.SplitPolicy.NEWLINE).forEach { message ->
                        channel.sendMessage(message).queue { super.addMessageToCleaner(it) }
                    }
                }
                4.toByte() -> {
                    channel.sendMessage(MENTION_CHANNEL_TO_SET)
                        .queue { addMessageToCleaner(it) }
                    sequenceNumber = 6
                }
                5.toByte() -> {
                    channel.sendMessage(MENTION_CHANNEL_TO_SET)
                        .queue { addMessageToCleaner(it) }
                    sequenceNumber = 7
                }
                6.toByte() -> {
                    channel.sendMessage("Please type the exact role name you want to set (please make sure the role name is unique).")
                        .queue { addMessageToCleaner(it) }
                    sequenceNumber = 8
                }
                7.toByte() -> {
                    channel.sendMessage("Please mention the channel you want to set.")
                        .queue { addMessageToCleaner(it) }
                    sequenceNumber = 9
                }
                8.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.resetGateSettings(guildId)
                    event.channel.sendMessage("Member approval gate settings wiped and disabled.").queue {
                        it.delete().queueAfter(1, TimeUnit.MINUTES)
                    }
                    destroy()
                }
                9.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.resetWelcomeSettings(guildId)
                    welcomeMessageService.removeAllWelcomeMessages(guildId)
                    event.channel.sendMessage("Welcome settings wiped and disabled.").queue {
                        it.delete().queueAfter(1, TimeUnit.MINUTES)
                    }
                    destroy()
                }
                10.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.resetAllSettings(guildId)
                    event.channel.sendMessage("Member gate module settings wiped and disabled.").queue {
                        it.delete().queueAfter(1, TimeUnit.MINUTES)
                    }
                    destroy()
                }
                11.toByte() -> {
                    sequenceNumber = 10
                    channel.sendMessage("Please enter the amount of hour(s) a user has to complete the joining process")
                        .queue {
                            addMessageToCleaner(it)
                        }
                }
                12.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.setPurgeTime(guildId, null)
                    destroy()
                }
                13.toByte() -> {
                    sequenceNumber = 11
                    channel.sendMessage("Please enter the amount of hour(s) before the user receives a reminder")
                        .queue {
                            addMessageToCleaner(it)
                        }
                }
                14.toByte() -> {
                    val guildId = (channel as TextChannel).guild.idLong
                    memberGateService.setReminderTime(guildId, null)
                    destroy()
                }
            }
        }

        @Transactional
        override fun onMessageReceived(event: MessageReceivedEvent) {
            super.onMessageReceived(event)
        }
    }

    /**
     * Sequence to review user answers
     */
    private inner class ReviewSequence(
        user: User,
        channel: MessageChannel,
        private val userId: Long
    ) : Sequence(user, channel), ReactionSequence, MessageSequence {

        /**
         * Asks the first question and checks if the user is in the review list.
         */
        init {
            memberGateQuestionRepository.findById(userId).ifPresentOrElse(
                {
                    val userQuestionAndAnswer = it.question + '\n' + it.answer
                    if (userQuestionAndAnswer.isNotBlank()) {
                        MessageBuilder().append("The user answered with the following question:\n")
                            .appendCodeBlock(userQuestionAndAnswer, "text")
                            .append("\nIf you want to approve the user respond with `approve`, to make the bot request the user to ask a new question respond with `reject` or to reject the user and take manual action answer with `noop` or use the reactions.")
                            .buildAll(MessageBuilder.SplitPolicy.NEWLINE).forEach { message ->
                                channel.sendMessage(message).queue { sendMessage ->
                                    super.addMessageToCleaner(sendMessage)
                                    sendMessage.addReaction("✅").queue()
                                    sendMessage.addReaction("❌").queue()
                                    sendMessage.addReaction("❓").queue()
                                }
                            }
                    } else {
                        super.destroy()
                        throw IllegalArgumentException("The user you tried to review is still in the list, but another moderator already declared the question wrong or the user rejoined.")
                    }
                },
                {
                    super.destroy()
                    throw IllegalArgumentException("The user you tried to review is not currently in the manual review list.")
                })
        }

        override fun onReactionReceivedDuringSequence(event: MessageReactionAddEvent) {
            if (!memberGateQuestionRepository.existsById(userId)) {
                throw IllegalStateException("The user is no longer in the queue; another moderator may have reviewed it already.")
            }
            when (event.reactionEmote.name) {
                "✅" -> {
                    accept(event.jda, event.guild)
                }
                "❌" -> {
                    reject(event.jda, event.guild)
                }
                "❓" -> {
                    reject(event.jda, event.guild, noOp = true)
                }
            }
        }

        /**
         * Review logic to approve members.
         */
        override fun onMessageReceivedDuringSequence(event: MessageReceivedEvent) {
            if (!memberGateQuestionRepository.existsById(userId)) {
                throw IllegalStateException("The user is no longer in the queue; another moderator may have reviewed it already.")
            }
            when (val messageContent: String = event.message.contentDisplay.lowercase(Locale.getDefault())) {
                "approve", "accept" -> {
                    accept(event.jda, event.guild)
                }
                "reject", "refuse" -> {
                    reject(event.jda, event.guild)
                }
                "noop" -> {
                    reject(event.jda, event.guild, noOp = true)
                }
                else -> {
                    throw IllegalArgumentException("Expecting one of the previously mentioned responses, but got \"$messageContent\" as response")
                }
            }
        }

        private fun reject(jda: JDA, guild: Guild, noOp: Boolean = false) {
            val member: Member? = guild.getMemberById(userId)
            val gateChannel = memberGateService.getGateChannel(guild.idLong, jda)
            if (member != null) {
                if (!noOp) {
                    gateChannel?.sendMessage("Your answer was incorrect " + member.user.asMention + ".  You can use the `!join` command to try again.")
                        ?.queue {
                            it.delete().queueAfter(1, TimeUnit.HOURS)
                        }
                }
            } else {
                super.channel.sendMessage("The user already left; no further action is needed.")
                    .queue { it.delete().queueAfter(1, TimeUnit.MINUTES) }
            }
            memberGateQuestionRepository.deleteById(userId)
            synchronized(informUserMessageIds) {
                val messageToRemove = informUserMessageIds.remove(userId)
                if (messageToRemove != null) {
                    gateChannel
                        ?.let { gateTextChannel ->
                            gateTextChannel.retrieveMessageById(messageToRemove).queue { it.delete().queue() }
                        }
                }
            }
            destroy()
        }

        private fun accept(jda: JDA, guild: Guild) {
            val member: Member? = guild.getMemberById(userId)
            if (member != null) {
                super.channel.sendMessage("The user has been approved.")
                    .queue { it.delete().queueAfter(15, TimeUnit.SECONDS) }
                accept(member)
            } else {
                super.channel.sendMessage("The user has left; no further action is needed.")
                    .queue { it.delete().queueAfter(15, TimeUnit.SECONDS) }
            }
            memberGateQuestionRepository.deleteById(userId)
            synchronized(informUserMessageIds) {
                val messageToRemove = informUserMessageIds.remove(userId)
                if (messageToRemove != null) {
                    memberGateService.getGateChannel(guild.idLong, jda)
                        ?.let { gateTextChannel ->
                            gateTextChannel.retrieveMessageById(messageToRemove).queue { it.delete().queue() }
                        }
                }
            }
            destroy()
        }
    }
}

