import dev.kord.common.entity.Snowflake
import dev.kord.rest.service.RestClient
import io.github.cdimascio.dotenv.dotenv
import java.io.PrintWriter
import java.net.Socket
import java.util.*
import kotlinx.coroutines.*
import java.util.regex.Pattern
import kotlin.math.min

@Suppress("RegExpUnnecessaryNonCapturingGroup")
const val urlRegex = "\\b(?:(?:https?|ftp)://)(?:\\S+(?::\\S*)?@)?(?:(?!(?:10|127)(?:\\.\\d{1,3}){3})(?!(?:169\\.254|192\\.168)(?:\\.\\d{1,3}){2})(?!172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})(?:[1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])(?:\\.(?:1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}(?:\\.(?:[1-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))|(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,}))\\.?)(?::\\d{2,5})?(?:[/?#]\\S*)?\\b"
val urlPattern = Pattern.compile(urlRegex)

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

    val kord = RestClient(discordToken)

    var reconnectIfFail = true
    var delay = 0
    val maxDelay = 30 * 60 * 1000

    launch {
        while (true) {
            try {
                val s = scanner.nextLine()
                val pieces = s.split(' ', limit = 4)
                if (pieces[0] == "PING") {
                    send.println("PONG " + pieces[1])
                }
                else {
                    when (pieces[1]) {
                        "PRIVMSG" -> {
                            if (!pieces[2].contains("#vietnamese")) {
                                continue
                            }
                            val msg = pieces[3].drop(1)
                                // sanitize
                                .replace("@everyone", "at-everyone")
                                .replace("@here", "at-here")
                                .replace(Regex("<@&(\\d{17,19})>")) {
                                    match -> "at-role-" + match.groupValues[1]
                                }
                                .replace(urlPattern.toRegex()) {
                                    match -> "<${match.groupValues.first()}>"
                                }

                            val author = pieces[0].drop(1).dropLast(11) // !cho@ppy.sh
                            for (ch in channels) {
                                try {
                                    kord.channel.createMessage(Snowflake(ch.toULong())) {
                                        content = "[$author] $msg"
                                    }
                                } catch (_: Exception) {

                                }
                            }
                        }

                        "001" -> {
                            delay = 1000
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
                if (delay != 0) {
                    println("Delaying reconnection by $delay ms.")
                }
                delay = min(delay + 1000, maxDelay)
                socket.close()
                try {
                    initialize()
                    sendInitialCommands(ircUsername, ircPassword, send)
                } catch (_: Exception) {}
            }
        }
    }
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