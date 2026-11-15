package dev.luna.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedConfigTest {
    private fun config(vararg pairs: Pair<String, Any?>) = ManagedConfig(mapOf(*pairs))

    @Test
    fun nothingSetMeansNothingIsManaged() {
        val c = ManagedConfig.NONE
        assertTrue(c.isEmpty)
        assertNull(c.server); assertNull(c.key); assertNull(c.userName); assertNull(c.wakeWords); assertNull(c.listenForCalls)
    }

    @Test
    fun theSchemaDefaultsLeaveEverythingToThePerson() {
        // what an MDM that sends every default back delivers (res/xml/app_restrictions.xml): none of it may count as an admin's choice
        val c = config(
            "setup_link" to "", "server_url" to "", "api_key" to "", "user_name" to "", "assistant_name" to "",
            "listen_for_calls" to "user", "listen_for_wake_words" to "user", "wake_words" to "", "wake_sensitivity" to "user",
            "greeting_cue" to "user", "popup_end_button" to "user", "hide_settings" to "user", "hide_app" to "user", "language" to "user", "language_only" to "user",
        )
        assertTrue(c.isEmpty)
        assertEquals(emptySet<String>(), c.managed)
    }

    @Test
    fun aSetupLinkGivesTheServerAndKey() {
        val c = config("setup_link" to "https://luna.example.com/#key=abc123")
        assertEquals("https://luna.example.com", c.server)
        assertEquals("abc123", c.key)
    }

    @Test
    fun explicitServerAndKeyBeatTheLink() {
        val c = config("setup_link" to "https://old.example.com/#key=old", "server_url" to "https://new.example.com/", "api_key" to "new")
        assertEquals("https://new.example.com", c.server)
        assertEquals("new", c.key)
    }

    @Test
    fun aServerThatIsNotAnHttpAddressIsIgnored() {
        assertNull(config("server_url" to "luna.example.com").server)
        assertNull(config("server_url" to "javascript:alert(1)").server)
        // ...and then the link, if there is one, is what is used
        assertEquals("https://luna.example.com", config("server_url" to "nope", "setup_link" to "https://luna.example.com/#key=k").server)
    }

    @Test
    fun switchesAcceptBooleansAndTheirTextAndIgnoreTheRest() {
        val c = config(
            "listen_for_calls" to true, "listen_for_wake_words" to "false", "greeting_cue" to " TRUE ", "popup_end_button" to "maybe",
        )
        assertEquals(true, c.listenForCalls)
        assertEquals(false, c.listenForWakeWords)
        assertEquals(true, c.greetingCue)
        assertNull(c.popupEndButton)
        assertEquals(setOf("listen_for_calls", "listen_for_wake_words", "greeting_cue"), c.managed)
    }

    @Test
    fun sensitivityIsAPercentInTheFormAndAFractionInTheApp() {
        assertEquals(-0.5f, config("wake_sensitivity" to "-50").wakeSensitivity)
        assertEquals(0f, config("wake_sensitivity" to 0).wakeSensitivity)
        assertEquals(0.5f, config("wake_sensitivity" to 50L).wakeSensitivity)
        assertEquals(1f, config("wake_sensitivity" to "400").wakeSensitivity)           // out of range is held to the ends
        assertEquals(-1f, config("wake_sensitivity" to -400).wakeSensitivity)
        assertNull(config("wake_sensitivity" to "loud").wakeSensitivity)
    }

    @Test
    fun wakeWordsComeAsAnArrayAListOrCommaSeparatedTextAndOnlyKnownOnesCount() {
        assertEquals(setOf("ron", "alexa"), config("wake_words" to arrayOf("ron", "alexa")).wakeWords)
        assertEquals(setOf("ron", "alexa"), config("wake_words" to listOf("ron", " alexa ")).wakeWords)
        assertEquals(setOf("rulon", "hey_jarvis"), config("wake_words" to "rulon, hey_jarvis").wakeWords)
        assertEquals(setOf("ron"), config("wake_words" to "ron, computer, ; drop table").wakeWords)    // unknown ids are dropped
    }

    @Test
    fun anEmptyWakeWordListIsNotAnAnswer() {
        // text "" means "the person decides" (the schema's default); an array with nothing in it is an admin turning every word off
        assertNull(config("wake_words" to "").wakeWords)
        assertEquals(emptySet<String>(), config("wake_words" to emptyArray<String>()).wakeWords)
        assertTrue("wake_words" in config("wake_words" to emptyArray<String>()).managed)
    }

    @Test
    fun namesAreTrimmedAndEmptyOnesIgnored() {
        val c = config("user_name" to "  Alex ", "assistant_name" to "   ")
        assertEquals("Alex", c.userName)
        assertNull(c.agentName)
    }

    @Test
    fun saysWhichSettingsAreManaged() {
        val c = config("server_url" to "https://luna.example.com", "api_key" to "k", "popup_end_button" to "false")
        assertEquals(setOf("server_url", "api_key", "popup_end_button"), c.managed)
        assertFalse(c.isEmpty)
        assertEquals(false, c.popupEndButton)
    }

    @Test
    fun theKeysAreTheOnesInTheSchema() {
        // the schema file is part of the contract with every admin's policy; these names must not drift from it
        val schema = checkNotNull(javaClass.classLoader?.getResourceAsStream("app_restrictions.xml") ?: java.io.File("src/main/res/xml/app_restrictions.xml").takeIf { it.exists() }?.inputStream()) { "schema missing" }
            .bufferedReader().readText()
        val inSchema = Regex("""android:key="([a-z_]+)"""").findAll(schema).map { it.groupValues[1] }.toSet()
        val inCode = setOf(
            ManagedConfig.KEY_SETUP_LINK, ManagedConfig.KEY_SERVER_URL, ManagedConfig.KEY_API_KEY, ManagedConfig.KEY_USER_NAME,
            ManagedConfig.KEY_ASSISTANT_NAME, ManagedConfig.KEY_LISTEN_FOR_CALLS, ManagedConfig.KEY_LISTEN_FOR_WAKE_WORDS,
            ManagedConfig.KEY_WAKE_WORDS, ManagedConfig.KEY_WAKE_SENSITIVITY, ManagedConfig.KEY_GREETING_CUE, ManagedConfig.KEY_POPUP_END_BUTTON,
            ManagedConfig.KEY_HIDE_SETTINGS, ManagedConfig.KEY_HIDE_APP,
            ManagedConfig.KEY_LANGUAGE, ManagedConfig.KEY_LANGUAGE_ONLY,
        )
        assertEquals(inCode, inSchema)
    }

    @Test
    fun hidingSettingsAndTheAppAreReadLikeAnyOtherSwitch() {
        val c = config("hide_settings" to "true", "hide_app" to true)
        assertEquals(true, c.hideSettings)
        assertEquals(true, c.hideApp)
        assertEquals(setOf("hide_settings", "hide_app"), c.managed)
        assertNull(config("hide_settings" to "user", "hide_app" to "user").hideApp)       // the default shows both
        assertEquals(false, config("hide_app" to "false").hideApp)                         // an admin can also say "show" explicitly
    }

    @Test
    fun theLauncherIconIsOnlyOffWhenTheAppIsHidden() {
        assertTrue(LauncherIcon.shouldShow(appHidden = false))
        assertFalse(LauncherIcon.shouldShow(appHidden = true))
    }

    @Test
    fun theLauncherIconIsAnAliasThatCanBeSwitchedOff() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val alias = Regex("""<activity-alias[^>]*android:name="\.Launcher"[\s\S]*?</activity-alias>""").find(manifest)?.value
        assertTrue("no .Launcher alias", alias != null)
        assertTrue(alias!!.contains("android.intent.category.LAUNCHER") && alias.contains("android:targetActivity=\".MainActivity\""))
        // MainActivity itself has no launcher filter (otherwise disabling the alias would leave an icon behind)
        val main = Regex("""<activity\s+android:name="\.MainActivity"[\s\S]*?(/>|</activity>)""").find(manifest)!!.value
        assertFalse(main.contains("LAUNCHER"))
        assertEquals("dev.luna.assistant.Launcher", LauncherIcon.ALIAS)
    }
}
