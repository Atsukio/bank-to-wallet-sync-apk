package app.banksync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Wallet API token, AES-GCM encrypted with a non-exportable Keystore key. */
    var token: String
        get() = decrypt(p.getString("token", null)).orEmpty()
        set(v) = p.edit().putString("token", if (v.isEmpty()) null else encrypt(v)).putBoolean("tokenVerified", false).apply()

    /** True once "Check connection" has passed for the saved token; any change of token clears it. */
    var tokenVerified: Boolean
        get() = p.getBoolean("tokenVerified", false) && token.isNotEmpty()
        set(v) = p.edit().putBoolean("tokenVerified", v).apply()

    /** Comma-separated; notifications containing any of these are skipped (OTPs etc). */
    var ignoreWords: String
        get() = p.getString("ignoreWords", "otp,verification code,one-time,passcode").orEmpty()
        set(v) = p.edit().putString("ignoreWords", v).apply()

    var paused: Boolean
        get() = p.getBoolean("paused", false)
        set(v) = p.edit().putBoolean("paused", v).apply()

    /** When off, a failed upload is marked failed immediately and waits for a manual retry. */
    var autoRetry: Boolean
        get() = p.getBoolean("autoRetry", true)
        set(v) = p.edit().putBoolean("autoRetry", v).apply()

    var maxAttempts: Int
        get() = p.getInt("maxAttempts", 10)
        set(v) = p.edit().putInt("maxAttempts", v.coerceIn(1, 100)).apply()

    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", false)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()

    /** Wait this long before uploading so bursts of notifications go out as one request. */
    var batchDelayMin: Int
        get() = p.getInt("batchDelayMin", 2)
        set(v) = p.edit().putInt("batchDelayMin", v.coerceIn(0, 120)).apply()

    var includeNote: Boolean
        get() = p.getBoolean("includeNote", true)
        set(v) = p.edit().putBoolean("includeNote", v).apply()

    /** Mark new records "uncleared" in Wallet so they stand out for review. */
    var markUncleared: Boolean
        get() = p.getBoolean("markUncleared", false)
        set(v) = p.edit().putBoolean("markUncleared", v).apply()

    var keepDays: Int
        get() = p.getInt("keepDays", 7)
        set(v) = p.edit().putInt("keepDays", v.coerceIn(1, 365)).apply()

    /** How far back "scan for missed alerts" looks (capped by [keepDays], see [CatchUp.cutoff]). */
    var catchUpHours: Int
        get() = p.getInt("catchUpHours", 24)
        set(v) = p.edit().putInt("catchUpHours", v.coerceIn(1, 168)).apply()

    /** When the first bank app was added: alerts from before this are never imported by a scan. */
    var catchUpSince: Long
        get() = p.getLong("catchUpSince", 0L)
        set(v) = p.edit().putLong("catchUpSince", v).apply()

    /** Skip money received when Wallet already has a matching record (the other leg of a transfer). */
    var skipExisting: Boolean
        get() = p.getBoolean("skipExisting", false)
        set(v) = p.edit().putBoolean("skipExisting", v).apply()

    /** Hold every captured transaction until the user approves it. */
    var reviewMode: Boolean
        get() = p.getBoolean("reviewMode", false)
        set(v) = p.edit().putBoolean("reviewMode", v).apply()

    /** Post a notification when a token is rejected or records could not be sent. */
    var alertsOn: Boolean
        get() = p.getBoolean("alertsOn", true)
        set(v) = p.edit().putBoolean("alertsOn", v).apply()

    var appLock: Boolean
        get() = p.getBoolean("appLock", false)
        set(v) = p.edit().putBoolean("appLock", v).apply()

    /** Set when Wallet rejects the token (401/403); cleared by the next successful upload. */
    var authError: Boolean
        get() = p.getBoolean("authError", false)
        set(v) = p.edit().putBoolean("authError", v).apply()

    /** The unrestricted-battery prompt is shown once; the status card and button stay available. */
    var batteryAsked: Boolean
        get() = p.getBoolean("batteryAsked", false)
        set(v) = p.edit().putBoolean("batteryAsked", v).apply()

    var notificationsAsked: Boolean
        get() = p.getBoolean("notificationsAsked", false)
        set(v) = p.edit().putBoolean("notificationsAsked", v).apply()

    /** The setup guide step the user reached, so reopening it carries on from there. */
    var onboardingStep: Int
        get() = p.getInt("onboardingStep", 0)
        set(v) = p.edit().putInt("onboardingStep", v).apply()

    var onboardingDone: Boolean
        get() = p.getBoolean("onboardingDone", false)
        set(v) = p.edit().putBoolean("onboardingDone", v).apply()

    // --- Bank app rules ---------------------------------------------------------------------

    /** Only packages that have a rule are ever inspected. */
    fun rules(): List<Rule> = cache().first

    fun rulesFor(pkg: String): List<Rule>? = cache().second[pkg]

    fun addRule(rule: Rule) {
        if (rules().isEmpty()) catchUpSince = System.currentTimeMillis()
        saveRules(rules().filterNot { it.pkg == rule.pkg && it.match.equals(rule.match, ignoreCase = true) } + rule)
    }

    fun removeRule(rule: Rule) = saveRules(rules() - rule)

    private fun saveRules(list: List<Rule>) {
        p.edit().putString("rules", rulesToJson(list).toString()).apply()
        cached = null
    }

    private fun cache(): Pair<List<Rule>, Map<String, List<Rule>>> = cached ?: run {
        val list = try {
            rulesFromJson(JSONArray(p.getString("rules", "[]")))
        } catch (e: Exception) {
            emptyList()
        }
        (list to list.groupBy { it.pkg }).also { cached = it }
    }

    // --- Category rules ---------------------------------------------------------------------

    fun categoryRules(): List<CategoryRule> = try {
        categoryRulesFromJson(JSONArray(p.getString("categoryRules", "[]")))
    } catch (e: Exception) {
        emptyList()
    }

    fun addCategoryRule(rule: CategoryRule) =
        saveCategoryRules(categoryRules().filterNot { it.contains.equals(rule.contains, ignoreCase = true) } + rule)

    fun removeCategoryRule(rule: CategoryRule) = saveCategoryRules(categoryRules() - rule)

    private fun saveCategoryRules(list: List<CategoryRule>) {
        p.edit().putString("categoryRules", categoryRulesToJson(list).toString()).apply()
    }

    // --- Export / import (never includes the token) -----------------------------------------

    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("rules", rulesToJson(rules()))
        .put("categoryRules", categoryRulesToJson(categoryRules()))
        .put(
            "settings",
            JSONObject()
                .put("ignoreWords", ignoreWords).put("autoRetry", autoRetry).put("maxAttempts", maxAttempts)
                .put("wifiOnly", wifiOnly).put("batchDelayMin", batchDelayMin).put("includeNote", includeNote)
                .put("markUncleared", markUncleared).put("keepDays", keepDays).put("reviewMode", reviewMode)
                .put("catchUpHours", catchUpHours).put("skipExisting", skipExisting)
                .put("alertsOn", alertsOn).put("appLock", appLock)
        )
        .toString(2)

    /** Throws if the text isn't a settings export; nothing is changed in that case. */
    fun importJson(text: String) {
        val root = JSONObject(text)
        val rules = rulesFromJson(root.getJSONArray("rules"))
        val cats = categoryRulesFromJson(root.optJSONArray("categoryRules") ?: JSONArray())
        val s = root.optJSONObject("settings") ?: JSONObject()
        if (rules().isEmpty() && rules.isNotEmpty()) catchUpSince = System.currentTimeMillis()
        saveRules(rules)
        saveCategoryRules(cats)
        catchUpHours = s.optInt("catchUpHours", catchUpHours)
        skipExisting = s.optBoolean("skipExisting", skipExisting)
        ignoreWords = s.optString("ignoreWords", ignoreWords)
        autoRetry = s.optBoolean("autoRetry", autoRetry)
        maxAttempts = s.optInt("maxAttempts", maxAttempts)
        wifiOnly = s.optBoolean("wifiOnly", wifiOnly)
        batchDelayMin = s.optInt("batchDelayMin", batchDelayMin)
        includeNote = s.optBoolean("includeNote", includeNote)
        markUncleared = s.optBoolean("markUncleared", markUncleared)
        keepDays = s.optInt("keepDays", keepDays)
        reviewMode = s.optBoolean("reviewMode", reviewMode)
        alertsOn = s.optBoolean("alertsOn", alertsOn)
        appLock = s.optBoolean("appLock", appLock)
    }

    private fun rulesToJson(list: List<Rule>) = JSONArray().also { arr ->
        list.forEach {
            arr.put(
                JSONObject().put("pkg", it.pkg).put("label", it.label).put("match", it.match)
                    .put("accountId", it.accountId).put("accountName", it.accountName)
                    .put("accountCurrency", it.accountCurrency).put("direction", it.direction)
                    .put("transferAccountId", it.transferAccountId).put("transferAccountName", it.transferAccountName)
            )
        }
    }

    private fun rulesFromJson(arr: JSONArray): List<Rule> = (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Rule(
            o.getString("pkg"), o.optString("label"), o.optString("match"),
            o.getString("accountId"), o.optString("accountName"), o.optString("accountCurrency"),
            o.optInt("direction"), o.optString("transferAccountId"), o.optString("transferAccountName")
        )
    }

    private fun categoryRulesToJson(list: List<CategoryRule>) = JSONArray().also { arr ->
        list.forEach {
            arr.put(
                JSONObject().put("contains", it.contains).put("categoryId", it.categoryId)
                    .put("categoryName", it.categoryName)
            )
        }
    }

    private fun categoryRulesFromJson(arr: JSONArray): List<CategoryRule> = (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        CategoryRule(o.getString("contains"), o.getString("categoryId"), o.optString("categoryName"))
    }

    // --- Token encryption -------------------------------------------------------------------

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        if (stored == null) return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (e: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "wallet_token"
        const val TRANSFORM = "AES/GCM/NoPadding"

        // Settings and the listener service share one process, so a plain cache is enough.
        @Volatile
        var cached: Pair<List<Rule>, Map<String, List<Rule>>>? = null
    }
}
