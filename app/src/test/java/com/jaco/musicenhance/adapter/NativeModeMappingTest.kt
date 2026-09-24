package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.kugoulite.KugouLiteRepeatApi
import com.jaco.musicenhance.adapter.kuwo.KuwoMediaControls
import com.jaco.musicenhance.adapter.kuwo.KuwoRepeatApi
import com.jaco.musicenhance.player.model.RepeatMode
import org.junit.Assert.*
import org.junit.Test

class NativeModeMappingTest {
    @Test fun identicalNativeNumbersHaveAppSpecificMeanings() {
        assertEquals(RepeatMode.LIST_LOOP, KugouLiteRepeatApi.decode(1))
        assertEquals(RepeatMode.SEQUENTIAL, KuwoRepeatApi.decode(1))
        assertEquals(RepeatMode.SINGLE_LOOP, KuwoRepeatApi.decode(0))
        assertEquals(RepeatMode.UNKNOWN, KugouLiteRepeatApi.decode(0))
        assertEquals(RepeatMode.UNKNOWN, KuwoRepeatApi.decode(-1))
        assertEquals(RepeatMode.UNKNOWN, KugouLiteRepeatApi.decode(99))
    }

    @Test fun favoriteActionsPreserveUnknownAndUnsupportedStates() {
        assertEquals(true, KuwoMediaControls.decodeFavorite(listOf("KW_CUSTOM_EVENT_CANCEL_FAV")))
        assertEquals(false, KuwoMediaControls.decodeFavorite(listOf("KW_CUSTOM_EVENT_FAV")))
        assertNull(KuwoMediaControls.decodeFavorite(emptyList()))
        assertNull(KuwoMediaControls.decodeFavorite(listOf("KW_CUSTOM_EVENT_UNSUPPORTED_FAV")))
        assertNull(KuwoMediaControls.decodeFavorite(listOf("KW_CUSTOM_EVENT_FAV", "KW_CUSTOM_EVENT_CANCEL_FAV")))
    }
}
