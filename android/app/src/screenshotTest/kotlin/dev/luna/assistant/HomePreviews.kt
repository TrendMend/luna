package dev.luna.assistant

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.luna.assistant.ui.HomeContent
import dev.luna.assistant.ui.SettingsContent
import dev.luna.assistant.ui.SetupContent
import dev.luna.assistant.ui.theme.LunaTheme

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun HomeLight() = LunaTheme(darkTheme = false) { HomeContent("Good morning", onTalk = {}, onSettings = {}, animateOrb = false) }

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun HomeDark() = LunaTheme(darkTheme = true) { HomeContent("Good evening", onTalk = {}, onSettings = {}, animateOrb = false) }

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun SetupLight() = LunaTheme(darkTheme = false) { SetupContent("", {}, null, {}, animateOrb = false) }

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun SetupErrorDark() = LunaTheme(darkTheme = true) {
    SetupContent("http://oops", {}, "That doesn't look like a setup link with a key.", {}, animateOrb = false)
}

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun SettingsLight() = LunaTheme(darkTheme = false) {
    SettingsContent("http://10.0.0.90:8080", userName = "Rulon", agentName = "", onUserName = {}, onAgentName = {}, callsOn = true, onCallsToggled = {}, onChangeServer = { null }, onBack = {},
        onAssistantSettings = {}, version = "0.1")
}

@PreviewTest
@Preview(widthDp = 392, heightDp = 800)
@Composable
fun SettingsDark() = LunaTheme(darkTheme = true) {
    SettingsContent("http://10.0.0.90:8080", userName = "Mary Ann", agentName = "Nova", onUserName = {}, onAgentName = {}, callsOn = false, onCallsToggled = {}, onChangeServer = { null }, onBack = {},
        onAssistantSettings = {}, version = "0.1")
}
