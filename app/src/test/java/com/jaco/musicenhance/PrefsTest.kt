package com.jaco.musicenhance

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PrefsTest {
    @Test fun conceptEditionRetainsOldSettingWithoutEnablingOtherApps() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("legacy", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("hook_kugou_music", true).commit()
        assertTrue(Prefs.isHookEnabled(preferences, Prefs.HOOK_KUGOU_LITE_MUSIC))
        assertFalse(Prefs.isHookEnabled(preferences, Prefs.HOOK_KUGOU_STANDARD_MUSIC))
        assertFalse(Prefs.isHookEnabled(preferences, Prefs.HOOK_QQ_MUSIC))
    }

    @Test fun explicitlyDisabledNewSettingOverridesEnabledLegacySetting() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("updated", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("hook_kugou_music", true)
            .putBoolean(Prefs.HOOK_KUGOU_LITE_MUSIC, false).commit()
        assertFalse(Prefs.isHookEnabled(preferences, Prefs.HOOK_KUGOU_LITE_MUSIC))
        assertFalse(Prefs.isHookEnabled(null, Prefs.HOOK_KUGOU_LITE_MUSIC))
    }

    @Test fun standardEditionHasAnIndependentSwitch() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("standard", Context.MODE_PRIVATE)
        preferences.edit().putBoolean(Prefs.HOOK_KUGOU_STANDARD_MUSIC, true).commit()
        assertTrue(Prefs.isHookEnabled(preferences, Prefs.HOOK_KUGOU_STANDARD_MUSIC))
        assertFalse(Prefs.isHookEnabled(preferences, Prefs.HOOK_KUGOU_LITE_MUSIC))
    }
}
