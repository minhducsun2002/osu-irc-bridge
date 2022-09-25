import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.gateway.DisconnectEvent
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.on
import io.github.cdimascio.dotenv.dotenv
import java.io.PrintWriter
import java.net.Socket
import java.util.*
import kotlinx.coroutines.*
import org.threeten.extra.AmountFormats
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlin.math.min

@Suppress("RegExpUnnecessaryNonCapturingGroup")
const val urlRegex = "\\b(?:(?:https?|ftp)://)(?:\\S+(?::\\S*)?@)?(?:(?!(?:10|127)(?:\\.\\d{1,3}){3})(?!(?:169\\.254|192\\.168)(?:\\.\\d{1,3}){2})(?!172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})(?:[1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])(?:\\.(?:1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}(?:\\.(?:[1-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))|(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,}))\\.?)(?::\\d{2,5})?(?:[/?#]\\S*)?\\b"
const val actionMessageRegex = "\u0001ACTION( (.+)?)?\u0001"
val urlPattern = Pattern.compile(urlRegex)!!
val actionMessagePattern = Pattern.compile(actionMessageRegex)!!

val dotenv = dotenv {
    ignoreIfMalformed = true
    ignoreIfMissing = true
}
val ircPassword: String = dotenv.get("IRC_PASSWORD")
val ircUsername: String = dotenv.get("IRC_USERNAME")
val discordToken: String = dotenv.get("DISCORD_TOKEN")
val channels: List<String> = dotenv.get("TARGET_CHANNELS").split(',')
    .stream()
    .map {
        it.trim()
    }
    .filter {
        it.isNotEmpty()
    }
    .toList()

// socket stuff
var socket = Socket("irc.ppy.sh", 6667)
var scanner = Scanner(socket.getInputStream())
var send = PrintWriter(socket.getOutputStream(), true)

@Suppress("BlockingMethodInNonBlockingContext")
fun main(): Unit = runBlocking {
    sendInitialCommands(ircUsername, ircPassword, send)

    val k = Kord(discordToken)
    val kord = k.rest

    var reconnectIfFail = true
    var reconnectionDelay = 0
    val maxDelay = 30 * 60 * 1000
    var lastPing = OffsetDateTime.now()

    suspend fun refreshStatus() {
        while (true) {
            delay(5000)
            val now = OffsetDateTime.now()
            val diff = Duration.between(lastPing, now)
            val humanizedDiff = AmountFormats.wordBased(diff, Locale.ENGLISH)
            k.editPresence {
                playing("Last ping : $humanizedDiff ago")
            }
        }
    }

    suspend fun work() {
        while (true) {
            try {
                yield()
                val s = scanner.nextLine()
                val pieces = s.split(' ', limit = 4)
                if (pieces[0] == "PING") {
                    send.println("PONG " + pieces[1])
                    lastPing = OffsetDateTime.now()
                }
                else {
                    when (pieces[1]) {
                        "PRIVMSG" -> {
                            if (!pieces[2].contains("#vietnamese")) {
                                continue
                            }

                            var msg = pieces[3].drop(1)
                                // sanitize
                                .replace("@everyone", "at-everyone")
                                .replace("@here", "at-here")
                                .replace(Regex("<@&(\\d{17,19})>")) {
                                        match -> "at-role-" + match.groupValues[1]
                                }
                                .replace(urlPattern.toRegex()) {
                                        match -> "<${match.groupValues.first()}>"
                                }

                            val action = actionMessagePattern.toRegex().find(msg)?.groups?.get(2)
                            if (action != null) {
                                msg = "(*) ${action.value}"
                            }

                            val author = pieces[0].drop(1).dropLast(11) // !cho@ppy.sh
                            for (ch in channels) {
                                try {
                                    kord.channel.createMessage(Snowflake(ch.toULong())) {
                                        content = "[$author] $msg"
                                    }
                                } catch (e: Exception) {
                                    println("! Failed to send msg to channel $ch : \n $e")
                                }
                            }
                        }

                        "001" -> {
                            reconnectionDelay = 1000
                            println(s) // welcome
                        }

                        "464" -> {
                            println("Wrong credentials. Check again.")
                            reconnectIfFail = false
                        }
                    }
                }
            } catch (e : NoSuchElementException) {
                if (!reconnectIfFail) {
                    println("Refusing to reconnect.")
                    throw e
                }

                println("The other side disconnected. Attempting reconnection.")
                if (reconnectionDelay != 0) {
                    println("Delaying reconnection by $reconnectionDelay ms.")
                }
                reconnectionDelay = min(reconnectionDelay + 1000, maxDelay)
                socket.close()
                try {
                    initialize()
                    sendInitialCommands(ircUsername, ircPassword, send)
                } catch (_: Exception) {}
            }
        }
    }

    var once = false
    k.on<ReadyEvent> {
        if (!once) {
            once = true
            val user = k.getSelf()
            println("Woke as ${user.username}#${user.discriminator}")
            launch { work() }
            launch { refreshStatus() }
        }
    }
    k.on<DisconnectEvent> {
        val now = OffsetDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        println("disconnected @ ${now.format(formatter)}")
    }
    k.login()
}


fun sendInitialCommands(ircUsername: String, ircPassword: String, output: PrintWriter) {
    output.println("PASS $ircPassword")
    output.println("NICK $ircUsername")
    output.println("JOIN #vietnamese")
}

fun initialize() {
    socket = Socket("irc.ppy.sh", 6667)
    scanner = Scanner(socket.getInputStream())
    send = PrintWriter(socket.getOutputStream(), true)
}