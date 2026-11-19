package org.claudevoice.mobile.plugins.sms.rules

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * ADR-CVM-010: one-time codes never leave the phone. Every sender, number and code here is made
 * up: the senders are invented names, short codes and numbers in the unused +47 00 range.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneTimeCodesTest {
    class Sample(val sender: String, val body: String, val why: String) {
        override fun toString() = why
    }

    /** Bank, delivery and login texts, which must be hidden. */
    fun codes() =
        listOf(
            Sample("Eksempelbank", "Engangskoden din er 482913. Den gjelder i 5 minutter.", "a bank's code"),
            Sample("Prøvepost", "Pakken din kan hentes. Hentekode: 7731", "a delivery's pick-up code"),
            Sample("DEMOAPP", "123456 is your Demoapp login code", "a code before its word"),
            Sample("+4700000001", "Din bekreftelse:\n90 41 22", "the word on the line before the code"),
            Sample("2244", "Svar 5512 for å fortsette", "four digits from a short code"),
            Sample("Demoapp", "Code: ４８２９１３", "fullwidth digits"),
            Sample("Demoapp", "Demoapp: ٤٨٢٩١٣", "Arabic-Indic digits from a named sender"),
            Sample("+4700000002", "PIN 0423", "a pin from a number"),
            Sample("+4700000002", "Your passcode is 31-77-90", "a code split by dashes"),
            Sample("+4700000003", "VERIFICATION CODE 556677", "a code word in capitals"),
            Sample("+4700000003", "BankID: 778899", "BankID"),
            Sample("+4700000004", "Logg inn med 4410", "logg inn with a no-break space"),
            Sample("+4700000004", "Your OTP\r\n881 204", "a code on the next line after a Windows line end"),
            Sample("Eksempelbank", "Kode 1234 5678 9012", "a long run of groups, each of four digits"),
            Sample("", "Bruk 662211", "a message whose sender is unknown"),
            Sample("+4700000005", "sikkerhetskode=19", "a code word with a short number glued to it"),
            Sample("+4700000008", "Your co​de 482913", "a zero-width space inside a code word"),
            Sample("+4700000008", "Din pass­ord: 4410", "a soft hyphen inside a code word"),
            Sample("+4700000008", "Bank⁠ID 778899", "a word joiner inside a code word"),
            Sample("+4700000009", "Your one‑time pass 1234", "a no-break hyphen in one-time"),
            Sample("+4700000009", "Your one–time pass 1234", "an en dash in one-time"),
        )

    @ParameterizedTest
    @MethodSource("codes")
    fun `a message holding a one-time code is hidden, and names only its sender`(sample: Sample) {
        assertEquals(
            Shown.Hidden(sample.sender, "a one-time code from ${sample.sender.ifBlank { "an unknown sender" }}"),
            OneTimeCodes.shown(sample.sender, sample.body),
        )
    }

    /** Ordinary texts, which must reach the owner as they are. */
    fun ordinary() =
        listOf(
            Sample("+4700000006", "Kommer du på middag i kveld?", "a friend's question"),
            Sample("+4700000006", "Husker du koden til garasjen?", "a code word with no digit"),
            Sample("+4700000007", "Det koster 1500 kr", "a price from a number, with no code word"),
            Sample("+4700000007", "Ny kode på døra\n\nVi ses 18", "digits two lines after the word"),
            Sample("2244", "Svar JA for å fortsette", "a short code with no digits"),
            Sample("Testbutikk", "Tilbud: 3 for 99,-", "runs shorter than four digits"),
        )

    @ParameterizedTest
    @MethodSource("ordinary")
    fun `an ordinary message is kept as it is`(sample: Sample) {
        assertEquals(Shown.Text(sample.body), OneTimeCodes.shown(sample.sender, sample.body))
    }

    @Test
    fun `from a number, a digit counts on a code word's own line, the line before or the line after`() {
        val hidden = Shown.Hidden("+4700000008", "a one-time code from +4700000008")
        assertEquals(hidden, OneTimeCodes.shown("+4700000008", "123456\nis your code"))
        assertEquals(hidden, OneTimeCodes.shown("+4700000008", "Husker du koden?\nDen er 18"))
        assertEquals(hidden, OneTimeCodes.shown("+4700000008", "Den er 18 sier du, koden?"))
        val twoBefore = "Vi ses 18\n\nHusker du koden?"
        assertEquals(Shown.Text(twoBefore), OneTimeCodes.shown("+4700000008", twoBefore))
    }

    @Test
    fun `the hub gets the line and the sender of a hidden message, and the body of a kept one`() {
        val hidden = OneTimeCodes.shown("Eksempelbank", "Kode 482913") as Shown.Hidden
        assertEquals("a one-time code from Eksempelbank", hidden.line)
        assertEquals("Eksempelbank", hidden.sender)
        assertEquals("Vi ses", (OneTimeCodes.shown("+4700000006", "Vi ses") as Shown.Text).body)
    }

    @Test
    fun `a sender is a short code with at most the bound's digits and no plus`() {
        assertEquals(true, OneTimeCodes.namedOrShort("2".repeat(OneTimeCodes.SHORT_CODE_DIGITS)))
        assertEquals(false, OneTimeCodes.namedOrShort("2".repeat(OneTimeCodes.SHORT_CODE_DIGITS + 1)))
        assertEquals(false, OneTimeCodes.namedOrShort("+2244"))
        assertEquals(true, OneTimeCodes.namedOrShort("Eksempel 1"))
        assertEquals(true, OneTimeCodes.namedOrShort(" "))
    }

    @Test
    fun `from a short code a run counts from four digits to eight`() {
        assertEquals(Shown.Text("Svar 551 nå"), OneTimeCodes.shown("2244", "Svar 551 nå"))
        assertEquals(Shown.Text("Ref 123456789"), OneTimeCodes.shown("2244", "Ref 123456789"))
        assertEquals(Shown.Hidden("2244", "a one-time code from 2244"), OneTimeCodes.shown("2244", "Ref 12345678"))
    }

    @Test
    fun `every code word ADR-CVM-010 lists hides a digit beside it`() {
        val words =
            listOf(
                "code", "kode", "otp", "one-time", "passcode", "verification", "bekreftelse", "passord", "pin",
                "engangskode", "sikkerhetskode", "logg inn", "login", "bankid", "token",
            )
        for (word in words) {
            assertEquals(
                Shown.Hidden("+4700000009", "a one-time code from +4700000009"),
                OneTimeCodes.shown("+4700000009", "Your $word 7"),
                word,
            )
        }
    }

    /**
     * Texts with dates, prices and words that only hold a code word. Masking too much is
     * allowed (ADR-CVM-010), so these are counted, not judged. The count goes to the build's
     * reports, which CI keeps with the run; it is never committed.
     */
    private val listed =
        listOf(
            Sample("Eksempelbank", "Fakturaen på 2450 kr forfaller 15.12.", "a bill's amount"),
            Sample("Prøvepost", "Pakken kommer 2026-11-20", "a delivery date"),
            Sample("+4700000010", "PIN-koden til sykkelen? Møtes kl 1830", "a meeting time beside pin"),
            Sample("+4700000010", "Login på festen kl 20, husk billett", "a time beside login"),
            Sample("+4700000011", "Shopping i morgen, 2 poser", "pin inside shopping"),
            Sample("Testbutikk", "Ordre 20261118 er sendt", "an order number"),
            Sample("Testbutikk", "Tilbud: 3 for 99,-", "a small price"),
            Sample("+4700000011", "Bursdag 24.12", "a birthday from a friend"),
            Sample("+4700000012", "Opinion poll: 1 question", "pin inside opinion"),
        )

    /**
     * Codes the rules give up on purpose: from a service number of seven or eight digits with no
     * plus and no code word. A short code is at most six digits, so that an ordinary mobile
     * number written without its plus is not one. These are counted too, to show what that costs.
     */
    private val givenUp =
        listOf(
            Sample("2200001", "482913", "a bare code from a seven-digit sender"),
            Sample("22000001", "Din 7731 gjelder i 10 min", "a code with no code word from an eight-digit sender"),
        )

    @Test
    fun `a bare code from a service number is hidden exactly when the number is within the short-code bound`() {
        for (sample in givenUp) {
            val short = sample.sender.length <= OneTimeCodes.SHORT_CODE_DIGITS
            assertEquals(short, OneTimeCodes.shown(sample.sender, sample.body) is Shown.Hidden, sample.why)
        }
    }

    @Test
    fun `texts with dates and prices, and codes the rules give up, are counted, never judged`() {
        val hidden = listed.filter { OneTimeCodes.shown(it.sender, it.body) is Shown.Hidden }
        val kept = givenUp.filter { OneTimeCodes.shown(it.sender, it.body) is Shown.Text }
        val path = System.getProperty("otpListedReport") ?: return
        File(path).apply { parentFile.mkdirs() }.writeText(
            "hidden ${hidden.size} of ${listed.size} listed texts with dates, prices or words holding a code word\n" +
                hidden.joinToString("") { "- ${it.why}\n" } +
                "kept ${kept.size} of ${givenUp.size} codes from a seven- or eight-digit sender with no plus\n" +
                kept.joinToString("") { "- ${it.why}\n" },
        )
    }
}
