package life.xstar.music.player.update

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Credentials are obfuscated in the APK and encrypted at rest with Android Keystore.
 * APK-embedded credentials cannot be made secret from someone who controls the APK;
 * the NAS account must therefore be read-only and limited to application resources
 * (updates and /App/Font), never personal music or writable NAS directories.
 */
internal class UpdateCredentialStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "xstar_update_credentials",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun username(): String = value("username", USERNAME)
    fun password(): String = value("password", PASSWORD)

    private fun value(name: String, encoded: String): String {
        return preferences.getString(name, null) ?: decode(encoded).also {
            preferences.edit().putString(name, it).apply()
        }
    }

    private fun decode(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val key = OBFUSCATION_KEY.toByteArray(Charsets.UTF_8)
        return bytes.mapIndexed { index, byte ->
            (byte.toInt() xor key[index % key.size].toInt()).toByte()
        }.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        // Obfuscation only prevents plain-text grep; Keystore protects the persisted copy.
        private const val OBFUSCATION_KEY = "xstar-update-v1"
        private const val USERNAME = "AAAAAAA="
        private const val PASSWORD = "IAAAAAAAREM="
    }
}
