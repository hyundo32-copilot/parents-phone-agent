package com.hyundo.parentsagent.android

import com.hyundo.parentsagent.BuildConfig

/** local.properties에서 빌드 때 주입된 비밀값. 소스에는 값이 없다. */
object AppConfig {
    val linerApiKey: String get() = BuildConfig.LINER_API_KEY
    val telegramBotToken: String get() = BuildConfig.TELEGRAM_BOT_TOKEN
    val telegramChatId: String get() = BuildConfig.TELEGRAM_CHAT_ID
}
