package spam.blocker.service

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.provider.Telephony
import android.content.ContentProvider
import android.database.Cursor
import android.net.Uri
import spam.blocker.db.SpamTable
import spam.blocker.service.checker.Checker
import spam.blocker.util.spf

/**
 * Small, local integration surface for QUIK.
 *
 * QUIK is the default SMS app and therefore owns the system SMS provider. It
 * asks SpamBlocker for a decision, then performs the delete itself when its
 * existing "Drop messages" option is enabled.
 */
object QuikIntegration {
    const val AUTHORITY = "spam.blocker.quik"
    const val METHOD_SCREEN_SMS = "screen_sms"
    const val METHOD_SET_ENABLED = "set_enabled"
    const val METHOD_BLOCK_NUMBERS = "block_numbers"
    const val METHOD_UNBLOCK_NUMBERS = "unblock_numbers"

    const val KEY_ADDRESS = "address"
    const val KEY_BODY = "body"
    const val KEY_SIM_SLOT = "sim_slot"
    const val KEY_SHOULD_BLOCK = "should_block"
    const val KEY_REASON = "reason"
    const val KEY_RESULT = "result"
    const val KEY_ANYTHING_WRONG = "anything_wrong"
    const val KEY_ENABLED = "enabled"
    const val KEY_NUMBERS = "numbers"
    const val KEY_ERROR = "error"

    private val QUIK_PACKAGES = setOf(
        "dev.octoshrimpy.quik",
        "dev.octoshrimpy.quik.fdroid",
        "dev.octoshrimpy.quik.debug",
    )

    fun isQuikDefaultSmsApp(ctx: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(ctx) in QUIK_PACKAGES

    fun isAuthorizedCaller(ctx: Context): Boolean {
        val callingPackages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
            ?.toSet()
            .orEmpty()
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(ctx)
        return defaultSmsPackage in QUIK_PACKAGES && callingPackages.contains(defaultSmsPackage)
    }
}

class QuikScreeningProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return error("provider_not_ready")
        if (!QuikIntegration.isAuthorizedCaller(ctx)) {
            return error("unauthorized_caller")
        }

        return try {
            when (method) {
                QuikIntegration.METHOD_SCREEN_SMS -> screenSms(ctx, extras)
                QuikIntegration.METHOD_SET_ENABLED -> setEnabled(ctx, extras)
                QuikIntegration.METHOD_BLOCK_NUMBERS -> updateNumbers(ctx, extras, block = true)
                QuikIntegration.METHOD_UNBLOCK_NUMBERS -> updateNumbers(ctx, extras, block = false)
                else -> error("unknown_method")
            }
        } catch (e: Exception) {
            error(e::class.java.simpleName)
        }
    }

    private fun screenSms(ctx: Context, extras: Bundle?): Bundle {
        val address = extras?.getString(QuikIntegration.KEY_ADDRESS).orEmpty()
        val body = extras?.getString(QuikIntegration.KEY_BODY).orEmpty()
        val simSlot = extras?.takeIf { it.containsKey(QuikIntegration.KEY_SIM_SLOT) }
            ?.getInt(QuikIntegration.KEY_SIM_SLOT)

        val global = spf.Global(ctx)
        if (!global.isGloballyEnabled || !global.isSmsEnabled) {
            return decision(shouldBlock = false, reason = "disabled")
        }

        val (result, _, anythingWrong) = Checker.checkSms(
            ctx = ctx,
            rawNumber = address,
            messageBody = body,
            simSlot = simSlot,
        )

        return decision(
            shouldBlock = result.shouldBlock(),
            reason = result.resultReasonStr(ctx),
            result = result.byType,
            anythingWrong = anythingWrong,
        )
    }

    private fun setEnabled(ctx: Context, extras: Bundle?): Bundle {
        val enabled = extras?.getBoolean(QuikIntegration.KEY_ENABLED, false) ?: false
        spf.QuikIntegration(ctx).isEnabled = enabled
        return Bundle().apply { putBoolean(QuikIntegration.KEY_ENABLED, enabled) }
    }

    private fun updateNumbers(ctx: Context, extras: Bundle?, block: Boolean): Bundle {
        val numbers = extras?.getStringArrayList(QuikIntegration.KEY_NUMBERS).orEmpty()
        numbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { number ->
            if (block) {
                if (SpamTable.findByNumber(ctx, number) == null) SpamTable.add(ctx, number)
            } else {
                SpamTable.findByNumber(ctx, number)?.let { SpamTable.deleteById(ctx, it.id) }
            }
        }
        return Bundle().apply { putBoolean(QuikIntegration.KEY_ENABLED, true) }
    }

    private fun decision(
        shouldBlock: Boolean,
        reason: String,
        result: Int = 0,
        anythingWrong: Boolean = false,
    ) = Bundle().apply {
        putBoolean(QuikIntegration.KEY_SHOULD_BLOCK, shouldBlock)
        putString(QuikIntegration.KEY_REASON, reason)
        putInt(QuikIntegration.KEY_RESULT, result)
        putBoolean(QuikIntegration.KEY_ANYTHING_WRONG, anythingWrong)
    }

    private fun error(message: String) = Bundle().apply {
        putBoolean(QuikIntegration.KEY_ERROR, true)
        putString(QuikIntegration.KEY_REASON, message)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?,
                       selectionArgs: Array<out String>?): Int = 0
}
