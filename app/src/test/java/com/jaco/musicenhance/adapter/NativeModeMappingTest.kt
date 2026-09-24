package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.kugoulite.KugouLiteRepeatSource
import com.jaco.musicenhance.adapter.kuwo.KuwoMediaControlSource
import com.jaco.musicenhance.adapter.kuwo.KuwoRepeatSource
import com.jaco.musicenhance.player.model.RepeatMode
import org.junit.Assert.*
import org.junit.Test

class NativeModeMappingTest {
    @Test fun identicalNativeNumbersHaveAppSpecificMeanings() {
        assertEquals(RepeatMode.LIST_LOOP, KugouLiteRepeatSource.decode(1))
        assertEquals(RepeatMode.SEQUENTIAL, KuwoRepeatSource.decode(1))
        assertEquals(RepeatMode.SINGLE_LOOP, KuwoRepeatSource.decode(0))
        assertEquals(RepeatMode.UNKNOWN, KugouLiteRepeatSource.decode(0))
        assertEquals(RepeatMode.UNKNOWN, KuwoRepeatSource.decode(-1))
        assertEquals(RepeatMode.UNKNOWN, KugouLiteRepeatSource.decode(99))
    }

    @Test fun favoriteActionsPreserveUnknownAndUnsupportedStates() {
        assertEquals(true, KuwoMediaControlSource.decodeFavorite(listOf("KW_CUSTOM_EVENT_CANCEL_FAV")))
        assertEquals(false, KuwoMediaControlSource.decodeFavorite(listOf("KW_CUSTOM_EVENT_FAV")))
        assertNull(KuwoMediaControlSource.decodeFavorite(emptyList()))
        assertNull(KuwoMediaControlSource.decodeFavorite(listOf("KW_CUSTOM_EVENT_UNSUPPORTED_FAV")))
        assertNull(KuwoMediaControlSource.decodeFavorite(listOf("KW_CUSTOM_EVENT_FAV", "KW_CUSTOM_EVENT_CANCEL_FAV")))
    }
}
