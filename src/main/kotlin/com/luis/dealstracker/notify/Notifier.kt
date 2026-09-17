package com.luis.dealstracker.notify

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop notifications, via macOS's notification centre.
 *
 * Chosen over email or a push service because this app already runs on Luis's Mac under
 * launchd, so a banner needs no accounts, no secrets and no third-party service.
 *
 * Two back ends, preferred first:
 *
 *  1. **`terminal-notifier`**, when installed. The only one of the two where clicking the
 *     banner does something: `-open` gives it a URL to follow, and `-group` makes a fresh
 *     alert for a watch *replace* that watch's previous one instead of stacking up.
 *  2. **`osascript`** otherwise. Works everywhere with no install, but AppleScript's
 *     `display notification` supports no click action of any kind, and the banner is posted
 *     as *Script Editor* — so clicking it opens an empty Script Editor window. That is
 *     confusing enough to be worth warning about rather than leaving to be discovered.
 *
 * **Values are passed as arguments, never interpolated into a script.** The older call in
 * `AmazonVerifier.askForHelp` builds an AppleScript string literal inline, which is safe only
 * because its message is a fixed sentence. Deal titles are not: they routinely contain double
 * quotes (`26" Smart TV`) and backslashes, either of which terminates or corrupts an
 * AppleScript literal — and a title is attacker-adjacent data, arriving from a retailer's
 * feed. `on run argv` keeps text as data.
 */
object Notifier {

    /**
     * Homebrew's bin is not on the PATH of a launchd-started process, which is how this app
     * actually runs day to day — so looking only at PATH would silently downgrade to the
     * osascript path in exactly the setup that matters most.
     */
    private val CANDIDATE_PATHS = listOf(
        "/opt/homebrew/bin/terminal-notifier",
        "/usr/local/bin/terminal-notifier",
    )

    private val terminalNotifier: String? by lazy {
        CANDIDATE_PATHS.firstOrNull { File(it).canExecute() }
            ?: runCatching {
                val p = ProcessBuilder("which", "terminal-notifier").start()
                p.waitFor(5, TimeUnit.SECONDS)
                p.inputStream.readBytes().decodeToString().trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
    }

    /** Warned once per process, not once per alert — this is a setup note, not an event. */
    private var warnedAboutClicks = false

    /**
     * One `-e` per AppleScript line: `on run argv` needs a real handler, and osascript treats
     * each `-e` as its own line. `sound name` is the sole literal, being fixed.
     */
    private val SCRIPT_LINES = listOf(
        "-e", "on run argv",
        "-e", "display notification (item 2 of argv) with title (item 1 of argv) sound name \"Ping\"",
        "-e", "end run",
    )

    /**
     * Shows a banner, returning whether it was dispatched.
     *
     * [openUrl] is followed when the banner is clicked, and ignored by the osascript
     * fallback, which cannot act on a click. [group] coalesces: a later banner with the same
     * group replaces the earlier one, so a watch that keeps improving leaves one current
     * notification rather than a stack of stale ones.
     *
     * Failure is reported rather than thrown: a missed banner must never take down the
     * poller, and the caller uses the result to decide whether to leave the alert
     * un-notified so the next cycle retries it.
     */
    fun notify(title: String, message: String, openUrl: String? = null, group: String? = null): Boolean {
        val notifier = terminalNotifier
        val command = if (notifier != null) {
            buildList {
                add(notifier)
                addAll(listOf("-title", title, "-message", message, "-sound", "Ping"))
                openUrl?.let { addAll(listOf("-open", it)) }
                // Namespaced so this app's groups cannot collide with another tool's.
                group?.let { addAll(listOf("-group", "deals-tracker:$it")) }
            }
        } else {
            if (!warnedAboutClicks) {
                warnedAboutClicks = true
                println(
                    "[notify] using osascript: banners will appear but clicking one opens Script " +
                        "Editor, because AppleScript notifications carry no click action. " +
                        "`brew install terminal-notifier` to make a click open the watch page.",
                )
            }
            listOf("osascript") + SCRIPT_LINES + listOf(title, message)
        }

        return runCatching { run(command) }.getOrElse {
            println("[notify] failed: ${it.message}")
            false
        }
    }

    private fun run(command: List<String>): Boolean {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Bounded, so a wedged notifier cannot stall the poll loop indefinitely.
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            println("[notify] ${command.first()} timed out")
            return false
        }
        if (process.exitValue() != 0) {
            val output = process.inputStream.readBytes().decodeToString().trim()
            println("[notify] ${command.first()} exited ${process.exitValue()}: $output")
            return false
        }
        return true
    }
}
