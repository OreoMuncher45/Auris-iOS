package com.music.auris.playback

internal actual fun <T> unmodifiableList(list: List<T>): List<T> = java.util.Collections.unmodifiableList(list)
