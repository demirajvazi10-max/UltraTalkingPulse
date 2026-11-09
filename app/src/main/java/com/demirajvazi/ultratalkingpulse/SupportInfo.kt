package com.demirajvazi.ultratalkingpulse

// Ultra — "How to support this project" guide, Android version.
// Same texts, links and account as SupportWindow.cs in the Windows Ultra apps, so the
// guide reads the same everywhere. Self-contained: English and Serbian texts live here.

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

object SupportInfo {
    const val SPONSORS_URL = "https://github.com/sponsors/demirajvazi10-max"
    const val KOFI_URL = "https://ko-fi.com/ultracreativesuite"
    const val BANK_ACCOUNT = "265-0000006185877-28"
    const val BANK_ACCOUNT_DIGITS = "265000000618587728" // digits only: safest to paste into a banking app
    const val BANK_RECIPIENT = "Demir Ajvazi"
    const val BANK_NAME = "Raiffeisen banka"

    fun isSerbian(activity: Activity): Boolean =
        activity.resources.configuration.locales[0].language == "sr"

    fun buttonText(sr: Boolean) = if (sr) "Kako podržati projekat" else "How to support this project"

    fun aboutLine(sr: Boolean) = if (sr)
        "Projekat možeš da podržiš uplatom na tekući račun (iz Srbije) ili platnom karticom, PayPal ne treba. Pogledaj \"Kako podržati projekat\"."
    else
        "You can support the project with an ordinary payment card, no PayPal account needed. See \"How to support this project\"."

    fun guideText(app: String, sr: Boolean): String = if (sr)
        "$app je besplatan i takav će i ostati. Ako ti pomaže i želiš da podržiš razvoj, evo tri načina. Podrška ide celoj Ultra porodici programa, ne samo ovom.\n\n" +
            "1. IZ SRBIJE: UPLATA NA TEKUĆI RAČUN (najlakše)\n" +
            "Primalac: $BANK_RECIPIENT\nRačun: $BANK_ACCOUNT\nBanka: $BANK_NAME\nSvrha uplate: Donacija za Ultra programe\n" +
            "Uplati iz mobilnog ili elektronskog bankarstva, kao bilo koji račun. Dugme \"Kopiraj broj računa\" ispod stavlja broj u međuspremnik, pa ga samo nalepiš. Ne treba nikakav nalog ni kartica za internet.\n\n" +
            "2. KARTICOM PREKO KO-FI (bez naloga)\n" +
            "$KOFI_URL\n" +
            "Pritisni \"Otvori Ko-fi\", upiši iznos, pritisni \"Donate\" i plati karticom (Visa ili Mastercard, kreditna ili debitna) ili PayPal-om. Nalog ti ne treba. Ovo je najlakši način za ljude iz inostranstva.\n\n" +
            "3. GITHUB SPONSORS (mesečno ili jednokratno)\n" +
            "$SPONSORS_URL\n" +
            "Za one koji imaju GitHub nalog (ili žele da ga naprave, besplatno je). Plaća se običnom platnom karticom, PayPal ne treba. Pritisni \"Otvori GitHub Sponsors\", prijavi se, izaberi mesečni iznos ili jednokratnu uplatu, upiši podatke sa kartice i potvrdi (banka će možda tražiti potvrdu u aplikaciji ili SMS kodom). Mesečna podrška se otkazuje kad god hoćeš u GitHub podešavanjima (Settings > Billing and licensing).\n\n" +
            "Možeš da pomogneš i besplatno: preporuči program drugima, prijavi problem ili daj projektu zvezdicu na GitHub-u.\n\nHvala!"
    else
        "$app is free and will stay free. If it helps you and you would like to support its development, here are three ways. Your support goes to the whole Ultra family of apps, not just this one.\n\n" +
            "1. BY CARD ON KO-FI (no account needed, easiest)\n" +
            "$KOFI_URL\n" +
            "Press \"Open Ko-fi\", type an amount, press \"Donate\" and pay with your card (Visa or Mastercard, credit or debit) or PayPal. You don't need an account.\n\n" +
            "2. GITHUB SPONSORS (monthly or one-time)\n" +
            "$SPONSORS_URL\n" +
            "For people who have a GitHub account (or want one, it's free). Paid with an ordinary payment card, no PayPal needed. Press \"Open GitHub Sponsors\", sign in, choose a monthly amount or a one-time payment, enter your card details and confirm (your bank may ask you to confirm in its app or with an SMS code). Monthly support can be cancelled at any time in your GitHub settings (Settings > Billing and licensing).\n\n" +
            "3. BANK TRANSFER FROM SERBIA\n" +
            "Recipient: $BANK_RECIPIENT\nAccount: $BANK_ACCOUNT\nBank: $BANK_NAME\nPurpose: Donation for Ultra apps\n" +
            "Meant for domestic transfers; international (SWIFT) transfers usually cost more in fees than a small donation is worth, so from abroad Ko-fi is the better choice.\n\n" +
            "You can also help for free: tell others about the app, report problems, or give the project a star on GitHub.\n\nThank you!"

    fun openUrl(activity: Activity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** Shows the guide: scrollable text a screen reader can read, plus large action buttons. */
    @Suppress("DEPRECATION") // announceForAccessibility is the simplest way to confirm the copy to TalkBack.
    fun show(activity: Activity, appName: String) {
        val sr = isSerbian(activity)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val text = TextView(activity).apply {
            text = guideText(appName, sr)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        column.addView(text)

        var dialog: AlertDialog? = null
        fun addButton(label: String, onClick: () -> Unit) {
            val button = Button(activity).apply {
                this.text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                minHeight = dp(52)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFF1A2233.toInt())
                    setStroke(dp(2), 0xFF4FCCC4.toInt())
                }
                setOnClickListener { onClick() }
            }
            column.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) },
            )
        }

        val copy = {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            val done = runCatching {
                clipboard.setPrimaryClip(ClipData.newPlainText(BANK_RECIPIENT, BANK_ACCOUNT_DIGITS))
            }.isSuccess
            text.announceForAccessibility(
                when {
                    !done && sr -> "Nije moglo da se kopira."
                    !done -> "Could not copy."
                    sr -> "Broj računa je kopiran: $BANK_ACCOUNT"
                    else -> "Account number copied: $BANK_ACCOUNT"
                },
            )
        }

        // Same button order as the Windows apps: Serbian puts the bank account first.
        if (sr) {
            addButton("Kopiraj broj računa", copy)
            addButton("Otvori Ko-fi") { openUrl(activity, KOFI_URL) }
            addButton("Otvori GitHub Sponsors") { openUrl(activity, SPONSORS_URL) }
        } else {
            addButton("Open Ko-fi") { openUrl(activity, KOFI_URL) }
            addButton("Open GitHub Sponsors") { openUrl(activity, SPONSORS_URL) }
            addButton("Copy bank account number", copy)
        }
        addButton(if (sr) "Zatvori" else "Close") { dialog?.dismiss() }

        val created = AlertDialog.Builder(activity)
            .setTitle(if (sr) "Kako podržati $appName" else "How to support $appName")
            .setView(ScrollView(activity).apply { addView(column) })
            .create()
        dialog = created
        created.show()
    }
}
