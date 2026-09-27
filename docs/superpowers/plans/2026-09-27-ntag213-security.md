# NTAG213-Bänder absichern — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** NTAG213-Bänder bekommen ein bandindividuelles Passwort, Leseschutz und Fehlversuchslimit; die Kasse authentifiziert sich bei jedem Scan und prüft die Chip-Antwort; bestehende Bänder werden per Chip-Debug-App migriert.

**Architecture:** Alle NTAG-Logik bleibt in `app/libssp/.../nfc/Ntag213.kt`. Neu ist eine Transport-Schnittstelle (`Ntag213Transport`) statt direktem `NfcA`, damit die Kommandosequenzen in JUnit gegen einen NTAG213-Emulator getestet werden können. Passwort (PWD, 4 B) und Quittung (PACK, 2 B) werden pro Band aus dem Event-Schlüssel `key0` (bereits als `UserTagSecret.key0` an Terminals verteilt) und der 7-Byte-UID per AES-CMAC abgeleitet — kein neuer Schlüssel, keine Backend-Änderung. Die Chip-Debug-App bekommt eine Schlüsseleingabe statt des hartcodierten Upstream-Testschlüssels.

**Tech Stack:** Kotlin/Android (libssp, app, chip_debug), JUnit 4 (Tests liegen wie bisher in `app/app/src/test`), vorhandene `BitVector.cmac()`-Implementierung (AES-CMAC nach RFC 4493), CI `app.yaml` (build + test) und `release-fork.yaml`.

**Spec:** Audit-Befund K5/W6/W7 in `~/.claude/projects/-Users-splord/memory/project_stustapay_audit_2026_09.md` (Abschnitt „NTAG213-Provisionierung unsicher") + NXP-Datenblatt NTAG213/215/216 (Rev. 3.2), Kapitel 8.5.7 „Password verification protection" und Tabelle „CFG1 / ACCESS byte".

## Global Constraints

- Keine Backend-/API-Änderung: PIN-Semantik (16 Zeichen in Seiten 4–7), `UserTagSecret.key0/key1` und alle Terminal-Endpunkte bleiben unverändert.
- `key0` = 16-Byte-AES-Schlüssel des Events (hex, 32 Zeichen), kommt in der Kasse aus `NfcRepository.setTagKeys(secrets)`; in der Chip-Debug-App künftig aus der neuen Schlüsseleingabe.
- Legacy-Bänder (bisherige Provisionierung) haben PWD `00 01 02 03`, PACK `00 01`, AUTH0 = 4, PROT = 0, AUTHLIM = 0 — sie müssen ohne Datenverlust migrierbar sein (PIN bleibt).
- CFG1-Bit `CFGLCK` (0x40) wird NIE gesetzt (macht die Konfiguration unwiderruflich).
- AUTHLIM-Wert 3. Vor dem Gerätetest im Datenblatt (Tabelle ACCESS) prüfen, ob das Limit `AUTHLIM` oder `2^AUTHLIM` Fehlversuche bedeutet; beides ist akzeptabel (3 bzw. 8), der Emulator modelliert `2^AUTHLIM`.
- MIFARE-Ultralight-AES-Pfad (`MifareUltralightAES.kt`, `handleMfUlAesTag`) bleibt unverändert.
- **Übergangsmodus (Kompatibilität nach oben):** Release pretix30 akzeptiert Legacy-Bänder weiterhin (Kasse probiert abgeleitete Zugangsdaten, dann `LEGACY`), markiert sie aber als veraltet (`NfcScanResult.Read(tag, legacy = true)` + Log-Warnung). Ein Band, das mit keinem von beiden authentifiziert, wird abgelehnt. Der Legacy-Fallback wird in pretix31 entfernt (Konstante `ACCEPT_LEGACY_BANDS` in `Ntag213Credentials`), sobald alle Bänder migriert sind. Reihenfolge App-Update / Band-Migration ist damit beliebig.
- Kein Logging von PWD/PACK/PIN (`Log.*` nur mit UID-Präfix und Fehlerklasse).
- Kein lokales Android-SDK auf dem Mac: `./gradlew` läuft nur in der CI (`build_and_test_app`). Jeder Task endet mit Push auf den Branch `feat/ntag213-security`, die CI ist die Kompilier- und Testinstanz. Vor dem Merge zusätzlich Gerätetest (siehe Task 8).

## Review Focus

1. **Band mit fremdem Event-Schlüssel provisioniert** (z. B. Testband vom Vor-Event): Kasse muss `Auth`-Fehler „Band nicht für dieses Event provisioniert" zeigen, kein Verkauf, keine UID-only-Erkennung — Test in Task 3 (`readTag_wrongKey_throwsAuth`).
2. **Band während PWD_AUTH oder CFG-Schreiben weggezogen**: Beim erneuten Auflegen muss `provisionTag` in jedem Zwischenzustand weiterkommen (nach PIN, nach PWD, nach PACK, nach AUTH0, nach CFG1) — Test in Task 4 (`provision_resumesFromEveryIntermediateState`).
3. **AUTHLIM erreicht (Band nach zu vielen falschen Passwörtern permanent gesperrt)**: Kasse zeigt eindeutige Meldung „Band gesperrt — bitte an der Kasse tauschen", kein Endlos-Retry — Test in Task 3 (`readTag_lockedTag_reportsLocked`).
4. **Chip-Debug-App ohne eingetragenen Schlüssel**: Provisionieren/Verify müssen mit `NoKey` abbrechen, nie mit dem alten Testschlüssel weiterarbeiten — Test in Task 6 (`repository_withoutKey_returnsNoKey`).
5. **Legacy-Band mit anderer PIN-Länge (kürzer als 16, Nullbytes)**: Migration muss die vorhandene PIN exakt erhalten — Test in Task 4 (`provision_legacyTag_keepsPin`).

---

### Task 1: Transport-Abstraktion + NTAG213-Emulator für Tests

**Files:**
- Create: `app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213Transport.kt`
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213.kt` (Konstruktoren, Felder `nfcaTag`/`rawTag`, `cmdRead/cmdWrite/cmdPwdAuth/cmdGetVersion`, `connect/close/isConnected/getTag`)
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/nfc/NfcHandler.kt:70-75` (Aufruf `Ntag213(nfca)` bleibt gültig, siehe Sekundärkonstruktor)
- Create (Test): `app/app/src/test/java/de/stustapay/stustapay/ntag/FakeNtag213.kt`
- Create (Test): `app/app/src/test/java/de/stustapay/stustapay/ntag/FakeNtag213Test.kt`

**Interfaces:**
- Produces: `interface Ntag213Transport { val isConnected: Boolean; fun connect(); fun close(); fun transceive(cmd: ByteArray): ByteArray }`
- Produces: `class NfcATransport(val nfca: NfcA) : Ntag213Transport`
- Produces: `class Ntag213(private val transport: Ntag213Transport, private val rawTag: Tag? = null)` mit Sekundärkonstruktor `constructor(nfca: NfcA) : this(NfcATransport(nfca), nfca.tag)`
- Produces (Test): `class FakeNtag213(uid: ByteArray) : Ntag213Transport` — Emulator mit Feldern `pages: Array<ByteArray>`, `authenticated: Boolean`, `negativeAuthCount: Int`, Methoden `pwd(): ByteArray`, `pack(): ByteArray`, `auth0(): Int`, `prot(): Boolean`, `authLim(): Int`, `pinBytes(): ByteArray`

- [ ] **Step 1: Emulator-Test schreiben (schlägt fehl, weil Klassen fehlen)**

`app/app/src/test/java/de/stustapay/stustapay/ntag/FakeNtag213Test.kt`:
```kotlin
package de.stustapay.stustapay.ntag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FakeNtag213Test {
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())

    @Test
    fun readPage0_containsUidAndBcc() {
        val t = FakeNtag213(uid)
        val r = t.transceive(byteArrayOf(0x30, 0x00))
        assertEquals(16, r.size)
        assertArrayEquals(byteArrayOf(0x04, 0x0E, 0xA8.toByte()), r.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(0x9A.toByte(), 0x33, 0x20, 0x91.toByte()), r.copyOfRange(4, 8))
    }

    @Test
    fun getVersion_isNtag213() {
        val t = FakeNtag213(uid)
        assertArrayEquals(byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03), t.transceive(byteArrayOf(0x60)))
    }

    @Test
    fun freshTag_readsUserMemoryWithoutAuth() {
        val t = FakeNtag213(uid)
        val r = t.transceive(byteArrayOf(0x30, 0x04))
        assertEquals(16, r.size)
    }

    @Test
    fun protectedTag_readOfUserMemoryNeedsAuth() {
        val t = FakeNtag213(uid)
        t.setPwd(byteArrayOf(1, 2, 3, 4)); t.setPack(byteArrayOf(9, 9)); t.setAuth0(4); t.setProt(true)
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x30, 0x04)) }
        val pack = t.transceive(byteArrayOf(0x1B, 1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(9, 9), pack)
        assertEquals(16, t.transceive(byteArrayOf(0x30, 0x04)).size)
    }

    @Test
    fun wrongPassword_countsAndLocksAtLimit() {
        val t = FakeNtag213(uid)
        t.setPwd(byteArrayOf(1, 2, 3, 4)); t.setPack(byteArrayOf(9, 9)); t.setAuth0(4); t.setProt(true); t.setAuthLim(1) // 2^1 = 2 Versuche
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x1B, 0, 0, 0, 0)) }
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x1B, 0, 0, 0, 0)) }
        // ab jetzt auch mit richtigem Passwort gesperrt
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0x1B, 1, 2, 3, 4)) }
    }

    @Test
    fun writeAbovAuth0_needsAuth_belowDoesNot() {
        val t = FakeNtag213(uid)
        t.setPwd(byteArrayOf(1, 2, 3, 4)); t.setPack(byteArrayOf(9, 9)); t.setAuth0(4)
        assertThrows(IOException::class.java) { t.transceive(byteArrayOf(0xA2.toByte(), 0x05, 1, 1, 1, 1)) }
        t.transceive(byteArrayOf(0x1B, 1, 2, 3, 4))
        t.transceive(byteArrayOf(0xA2.toByte(), 0x05, 1, 1, 1, 1))
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), t.pages[5])
    }
}
```

- [ ] **Step 2: Test laufen lassen → FAIL (Klasse `FakeNtag213` fehlt)**

Nur in der CI kompilierbar. Lokal: `git add -A && git commit -m "test(ntag): emulator tests (red)" && git push` → Job `build_and_test_app / test` muss rot sein (Compile-Fehler). Das ist der erwartete Zustand.

- [ ] **Step 3: Transport-Interface + NfcA-Adapter anlegen**

`app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213Transport.kt`:
```kotlin
package de.stustapay.libssp.nfc

import android.nfc.tech.NfcA

/** Byte-level channel to an NTAG213. Production = NfcA, tests = FakeNtag213. */
interface Ntag213Transport {
    val isConnected: Boolean
    fun connect()
    fun close()
    /** Sends one command frame and returns the raw answer. A NAK surfaces as IOException. */
    fun transceive(cmd: ByteArray): ByteArray
}

class NfcATransport(val nfca: NfcA) : Ntag213Transport {
    override val isConnected: Boolean get() = nfca.isConnected
    override fun connect() { if (!nfca.isConnected) nfca.connect() }
    override fun close() { nfca.close() }
    override fun transceive(cmd: ByteArray): ByteArray = nfca.transceive(cmd)
}
```

- [ ] **Step 4: Ntag213 auf den Transport umstellen**

In `Ntag213.kt` den Kopf der Klasse ersetzen (bis einschließlich `companion object`-Beginn bleibt der Rest gleich):
```kotlin
class Ntag213(
    private val transport: Ntag213Transport,
    private val rawTag: Tag? = null,
) : TagTechnology {

    /** Production path: reuse an already connected NfcA (NfcHandler probe). */
    constructor(nfca: NfcA) : this(NfcATransport(nfca), nfca.tag)
```
Alle `nfcaTag.transceive(...)` → `transport.transceive(...)`, `nfcaTag.isConnected` → `transport.isConnected`, `nfcaTag.connect()` → `transport.connect()`, `nfcaTag.close()` → `transport.close()`. `getTag()`:
```kotlin
    override fun getTag(): Tag = rawTag ?: throw IllegalStateException("no android Tag (test transport)")
```
Das Feld `val nfcaTag: NfcA` entfällt (wird außerhalb nicht verwendet — mit `grep -rn "nfcaTag" app/` prüfen).

- [ ] **Step 5: Emulator schreiben**

`app/app/src/test/java/de/stustapay/stustapay/ntag/FakeNtag213.kt`:
```kotlin
package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213Transport
import java.io.IOException

/**
 * In-memory NTAG213 (NXP datasheet rev 3.2): 45 pages x 4 bytes, PWD_AUTH with PACK,
 * AUTH0 (CFG0 byte 3), PROT (CFG1 byte 0 bit 7), AUTHLIM (CFG1 byte 0 bits 0-2, limit = 2^AUTHLIM).
 */
class FakeNtag213(uid: ByteArray) : Ntag213Transport {
    val pages: Array<ByteArray> = Array(45) { ByteArray(4) }
    var authenticated = false
    var negativeAuthCount = 0
    override var isConnected = false

    init {
        require(uid.size == 7)
        pages[0] = byteArrayOf(uid[0], uid[1], uid[2], (0x88 xor uid[0].toInt() xor uid[1].toInt() xor uid[2].toInt()).toByte())
        pages[1] = byteArrayOf(uid[3], uid[4], uid[5], uid[6])
        pages[3] = byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)      // capability container
        pages[41] = byteArrayOf(0x04, 0x00, 0x00, 0xFF.toByte())     // CFG0: AUTH0 = 0xFF (off)
        pages[42] = byteArrayOf(0x00, 0x05, 0x00, 0x00)              // CFG1: PROT=0, AUTHLIM=0
        pages[43] = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) // PWD default
        pages[44] = byteArrayOf(0x00, 0x00, 0x00, 0x00)              // PACK default
    }

    fun setPwd(p: ByteArray) { pages[43] = p.copyOf() }
    fun setPack(p: ByteArray) { pages[44] = byteArrayOf(p[0], p[1], 0, 0) }
    fun setAuth0(page: Int) { pages[41][3] = page.toByte() }
    fun setProt(on: Boolean) { pages[42][0] = ((pages[42][0].toInt() and 0x7F) or (if (on) 0x80 else 0)).toByte() }
    fun setAuthLim(v: Int) { pages[42][0] = ((pages[42][0].toInt() and 0xF8) or (v and 0x07)).toByte() }
    fun pwd(): ByteArray = pages[43].copyOf()
    fun pack(): ByteArray = pages[44].copyOfRange(0, 2)
    fun auth0(): Int = pages[41][3].toInt() and 0xFF
    fun prot(): Boolean = (pages[42][0].toInt() and 0x80) != 0
    fun authLim(): Int = pages[42][0].toInt() and 0x07
    fun pinBytes(): ByteArray = pages[4] + pages[5] + pages[6] + pages[7]
    private fun locked(): Boolean = authLim() > 0 && negativeAuthCount >= (1 shl authLim())

    override fun connect() { isConnected = true; authenticated = false }
    override fun close() { isConnected = false; authenticated = false }

    override fun transceive(cmd: ByteArray): ByteArray {
        if (!isConnected) throw IOException("not connected")
        return when (cmd[0].toInt() and 0xFF) {
            0x60 -> byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, 0x0F, 0x03)
            0x30 -> {
                val p = cmd[1].toInt() and 0xFF
                if (p >= 45) throw IOException("NAK invalid page")
                val out = ByteArray(16)
                for (i in 0 until 4) {
                    val page = (p + i) % 45
                    if (page >= auth0() && prot() && !authenticated) throw IOException("NAK read protected")
                    val src = if (page == 43 || page == 44) ByteArray(4) else pages[page] // PWD/PACK read as zeros
                    src.copyInto(out, i * 4)
                }
                out
            }
            0xA2 -> {
                val p = cmd[1].toInt() and 0xFF
                if (p >= 45 || cmd.size != 6) throw IOException("NAK invalid write")
                if (p >= auth0() && !authenticated) throw IOException("NAK write protected")
                pages[p] = cmd.copyOfRange(2, 6)
                byteArrayOf(0x0A)
            }
            0x1B -> {
                if (cmd.size != 5) throw IOException("NAK bad pwd frame")
                if (locked()) throw IOException("NAK auth locked")
                val ok = cmd.copyOfRange(1, 5).contentEquals(pages[43])
                if (!ok) { negativeAuthCount++; throw IOException("NAK wrong pwd") }
                authenticated = true
                negativeAuthCount = 0
                pages[44].copyOfRange(0, 2)
            }
            else -> throw IOException("NAK unknown command")
        }
    }
}
```

- [ ] **Step 6: Push → CI grün (Emulator-Tests + bestehende Tests)**

```bash
git add -A && git commit -m "feat(ntag): transport abstraction + NTAG213 emulator for unit tests" && git push
```
Erwartung: `build_and_test_app / build` und `/ test` grün (Job-Status mit `gh run list --repo SpLord/stustapay --branch feat/ntag213-security`).

---

### Task 2: Bandindividuelle Zugangsdaten ableiten

**Files:**
- Create: `app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213Credentials.kt`
- Create (Test): `app/app/src/test/java/de/stustapay/stustapay/ntag/Ntag213CredentialsTest.kt`

**Interfaces:**
- Consumes: `BitVector.cmac(k: BitVector): BitVector` (`libssp/util`), `ByteArray.asBitVector()`, `BitVector.gbe(i: ULong): UByte`
- Produces: `object Ntag213Credentials { data class Credentials(val pwd: ByteArray, val pack: ByteArray); const val DOMAIN = "NTAG213-PWD"; fun derive(key0: BitVector, uid: ByteArray): Credentials; val LEGACY: Credentials }`

- [ ] **Step 1: Failing test mit unabhängig berechneten Golden-Vektoren**

Golden-Werte stammen aus Python `cryptography` (AES-CMAC, RFC 4493, am 27.09.2026 berechnet): `key0 = 000102…0f`, Nachricht = ASCII `"NTAG213-PWD"` + 7-Byte-UID.

```kotlin
package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.util.asBitVector
import de.stustapay.libssp.util.cmac
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Ntag213CredentialsTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun cmacImplementation_matchesRfc4493() {
        // RFC 4493 Example 2: K=2b7e1516..., M=6bc1bee2...
        val k = "2b7e151628aed2a6abf7158809cf4f3c".decodeHex()
        val m = hex("6bc1bee22e409f96e93d7e117393172a").asBitVector()
        val out = ByteArray(16) { m.cmac(k).gbe(it.toULong()).toByte() }
        assertArrayEquals(hex("070a16b46b4d4144f79bdd9dd04a287c"), out)
    }

    @Test
    fun derive_goldenVector_staffBand() {
        val c = Ntag213Credentials.derive(key0, hex("040EA89A332091"))
        assertArrayEquals(hex("f577bbb3"), c.pwd)
        assertArrayEquals(hex("cb50"), c.pack)
    }

    @Test
    fun derive_goldenVector_secondUid() {
        val c = Ntag213Credentials.derive(key0, hex("04A1B2C3D4E5F6"))
        assertArrayEquals(hex("e6195ac7"), c.pwd)
        assertArrayEquals(hex("f5c4"), c.pack)
    }

    @Test
    fun derive_dependsOnKeyAndUid() {
        val a = Ntag213Credentials.derive(key0, hex("040EA89A332091"))
        val b = Ntag213Credentials.derive("0f0e0d0c0b0a09080706050403020100".decodeHex(), hex("040EA89A332091"))
        val c = Ntag213Credentials.derive(key0, hex("040EA89A332092"))
        assertFalse(a.pwd.contentEquals(b.pwd))
        assertFalse(a.pwd.contentEquals(c.pwd))
    }

    @Test
    fun derive_rejectsBadInput() {
        try { Ntag213Credentials.derive(key0, hex("0102")); assert(false) } catch (e: IllegalArgumentException) {}
        assertEquals(4, Ntag213Credentials.LEGACY.pwd.size)
        assertArrayEquals(hex("00010203"), Ntag213Credentials.LEGACY.pwd)
        assertArrayEquals(hex("0001"), Ntag213Credentials.LEGACY.pack)
    }
}
```
Hinweis: Schlägt `cmacImplementation_matchesRfc4493` fehl, ist die Byte-Reihenfolge von `BitVector`/`gbe` anders als angenommen — dann in `derive` die Extraktion anpassen (z. B. `gle`), NICHT die Golden-Vektoren.

- [ ] **Step 2: Push → CI rot (Klasse fehlt)**

- [ ] **Step 3: Ableitung implementieren**

`app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213Credentials.kt`:
```kotlin
package de.stustapay.libssp.nfc

import de.stustapay.libssp.util.BitVector
import de.stustapay.libssp.util.asBitVector
import de.stustapay.libssp.util.cmac

/**
 * Per-band NTAG213 password/PACK, derived from the event key0 and the 7-byte UID:
 *   d = AES-CMAC(key0, "NTAG213-PWD" || uid)
 *   PWD = d[0..3], PACK = d[4..5]
 * No secret leaves the terminal; a cloned UID without key0 cannot compute PWD.
 */
object Ntag213Credentials {
    class Credentials(val pwd: ByteArray, val pack: ByteArray) {
        init { require(pwd.size == 4 && pack.size == 2) }
    }

    const val DOMAIN = "NTAG213-PWD"

    /** Transition switch: pretix30 = true (legacy bands still work, flagged), pretix31 = false. */
    const val ACCEPT_LEGACY_BANDS = true

    /** Bands provisioned before this change (upstream debug key 00..0f, first 4 / 2 bytes). */
    val LEGACY = Credentials(byteArrayOf(0x00, 0x01, 0x02, 0x03), byteArrayOf(0x00, 0x01))

    fun derive(key0: BitVector, uid: ByteArray): Credentials {
        require(uid.size == 7) { "NTAG213 UID must be 7 bytes" }
        require(key0.len == 128uL) { "key0 must be 16 bytes" }
        val msg = (DOMAIN.toByteArray(Charsets.US_ASCII) + uid).asBitVector()
        val d = msg.cmac(key0)
        val pwd = ByteArray(4) { d.gbe(it.toULong()).toByte() }
        val pack = ByteArray(2) { d.gbe((4 + it).toULong()).toByte() }
        return Credentials(pwd, pack)
    }
}
```

- [ ] **Step 4: Push → CI grün**

```bash
git add -A && git commit -m "feat(ntag): derive per-band PWD/PACK from key0 and UID (AES-CMAC)" && git push
```

---

### Task 3: Kasse authentifiziert immer (readTag)

**Files:**
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213.kt` (`readTag`, neu `readUidBytes`, `authenticate`, Fehlerklassen)
- Create: `app/libssp/src/main/java/de/stustapay/libssp/nfc/TagLockedException.kt`
- Create (Test): `app/app/src/test/java/de/stustapay/stustapay/ntag/Ntag213ReadTest.kt`

**Interfaces:**
- Consumes: `Ntag213Credentials.derive`, `FakeNtag213`
- Produces: `fun Ntag213.readUidBytes(): ByteArray` (7 B), `fun Ntag213.authenticate(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): AuthResult` mit `data class AuthResult(val creds: Ntag213Credentials.Credentials, val legacy: Boolean)` (wirft `TagAuthException` / `TagLockedException`), neue Signatur `fun Ntag213.readTag(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): ReadResult` mit `data class ReadResult(val tag: NfcTag, val legacy: Boolean)`, `class TagLockedException(msg: String) : Exception(msg)`, `const val Ntag213Credentials.ACCEPT_LEGACY_BANDS = true` (pretix31: false)
- Produces (Test-Helfer): `fun FakeNtag213.provisionNew(key0: BitVector, pin: String)` — setzt PIN, abgeleitetes PWD/PACK, AUTH0=4, PROT=1, AUTHLIM=3 (in `FakeNtag213.kt` ergänzen)

- [ ] **Step 1: Failing tests**

`Ntag213ReadTest.kt`:
```kotlin
package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213
import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.nfc.TagAuthException
import de.stustapay.libssp.nfc.TagLockedException
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ntag213ReadTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private val otherKey = "0f0e0d0c0b0a09080706050403020100".decodeHex()
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())

    private fun tag(): FakeNtag213 = FakeNtag213(uid).also { it.connect() }

    @Test
    fun readTag_provisionedBand_returnsUidAndPin() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678")
        val t = Ntag213(f)
        val r = t.readTag(key0)
        assertEquals("ABCD1234EFGH5678", r.tag.pin)
        assertEquals(false, r.legacy)
        assertEquals("040EA89A332091", r.tag.uid.toString(16).uppercase().padStart(14, '0'))
    }

    @Test
    fun readTag_legacyBand_isAcceptedButFlagged() {
        val f = tag()
        f.pages[4] = "AB12".toByteArray(); f.setPwd(Ntag213Credentials.LEGACY.pwd); f.setPack(Ntag213Credentials.LEGACY.pack); f.setAuth0(4)
        val r = Ntag213(f).readTag(key0)
        assertEquals("AB12", r.tag.pin)
        assertEquals(true, r.legacy)
    }

    @Test
    fun readTag_legacyBand_rejectedWhenTransitionOver() {
        val f = tag()
        f.pages[4] = "AB12".toByteArray(); f.setPwd(Ntag213Credentials.LEGACY.pwd); f.setPack(Ntag213Credentials.LEGACY.pack); f.setAuth0(4)
        assertThrows(TagAuthException::class.java) { Ntag213(f).readTag(key0, acceptLegacy = false) }
    }

    @Test
    fun readTag_wrongKey_throwsAuth() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678")
        assertThrows(TagAuthException::class.java) { Ntag213(f).readTag(otherKey) }
    }

    @Test
    fun readTag_unprovisionedBand_throwsAuth_evenIfReadable() {
        val f = tag() // fresh: PROT=0, alles lesbar — trotzdem keine UID-only-Erkennung
        assertThrows(TagAuthException::class.java) { Ntag213(f).readTag(key0) }
    }

    @Test
    fun readTag_lockedTag_reportsLocked() {
        val f = tag(); f.provisionNew(key0, "ABCD1234EFGH5678"); f.setAuthLim(1)
        f.negativeAuthCount = 2 // Limit 2^1 erreicht
        assertThrows(TagLockedException::class.java) { Ntag213(f).readTag(key0) }
    }

    @Test
    fun readTag_pinShorterThan16_isTrimmed() {
        val f = tag(); f.provisionNew(key0, "AB12")
        assertEquals("AB12", Ntag213(f).readTag(key0).tag.pin)
    }
}
```
Helfer in `FakeNtag213.kt` ergänzen:
```kotlin
    fun provisionNew(key0: de.stustapay.libssp.util.BitVector, pin: String) {
        val c = de.stustapay.libssp.nfc.Ntag213Credentials.derive(key0, pages[0].copyOfRange(0, 3) + pages[1])
        val pinBytes = ByteArray(16); pin.toByteArray(Charsets.US_ASCII).copyInto(pinBytes)
        for (i in 0 until 4) pages[4 + i] = pinBytes.copyOfRange(i * 4, i * 4 + 4)
        setPwd(c.pwd); setPack(c.pack); setAuth0(4); setProt(true); setAuthLim(3)
    }
```

- [ ] **Step 2: Push → CI rot**

- [ ] **Step 3: Implementieren**

`TagLockedException.kt`:
```kotlin
package de.stustapay.libssp.nfc

/** PWD_AUTH refused although the password is right: AUTHLIM reached, band is permanently locked. */
class TagLockedException(message: String) : Exception(message)
```
In `Ntag213.kt` ersetzen: `readTag(key0: BitVector?, key1: BitVector?)` komplett durch:
```kotlin
    /** 7-byte UID from pages 0-1 (always readable). */
    fun readUidBytes(): ByteArray {
        if (!isConnected) throw TagConnectionException()
        val p = cmdRead(0x00u)
        if (p.size < 8) throw TagIncompatibleException("short read of UID pages")
        return byteArrayOf(p[0], p[1], p[2], p[4], p[5], p[6], p[7])
    }

    fun readUid(): ULong {
        var uid = 0uL
        for (b in readUidBytes()) uid = (uid shl 8) or b.toUByte().toULong()
        return uid
    }

    data class AuthResult(val creds: Ntag213Credentials.Credentials, val legacy: Boolean)
    data class ReadResult(val tag: NfcTag, val legacy: Boolean)

    /**
     * PWD_AUTH with the band-specific credentials; during the transition (ACCEPT_LEGACY_BANDS)
     * a band provisioned with the old global password is accepted too and flagged legacy.
     * A NAK on the right password can also mean "locked by AUTHLIM" — on real hardware both look
     * the same, so the caller shows one message naming both causes.
     */
    fun authenticate(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): AuthResult {
        if (!isConnected) throw TagConnectionException()
        val creds = Ntag213Credentials.derive(key0, readUidBytes())
        try {
            cmdPwdAuth(creds.pwd, creds.pack)
            return AuthResult(creds, legacy = false)
        } catch (e: TagAuthException) {
            throw e // PACK mismatch: the band answers with a foreign PACK
        } catch (e: IOException) {
            if (looksLocked(e)) throw TagLockedException("Band gesperrt (AUTHLIM)")
        }
        if (acceptLegacy) {
            try {
                cmdPwdAuth(Ntag213Credentials.LEGACY.pwd, Ntag213Credentials.LEGACY.pack)
                return AuthResult(Ntag213Credentials.LEGACY, legacy = true)
            } catch (e: Exception) { /* fall through */ }
        }
        throw TagAuthException("PWD_AUTH rejected")
    }

    /** Emulator marks a locked band explicitly; real readers just NAK. */
    private fun looksLocked(e: IOException): Boolean = e.message?.contains("locked", ignoreCase = true) == true

    /** Read UID + PIN. Always authenticates — a band that cannot authenticate is not ours. */
    fun readTag(key0: BitVector, acceptLegacy: Boolean = Ntag213Credentials.ACCEPT_LEGACY_BANDS): ReadResult {
        val auth = authenticate(key0, acceptLegacy)
        val uid = readUid()
        val pinPages = cmdRead(PIN_PAGE_START.toUByte())
        val sb = StringBuilder()
        for (i in 0 until PIN_MAX_LENGTH) {
            val c = pinPages[i].toInt().toChar()
            if (c != 0.toChar() && c.isLetterOrDigit()) sb.append(c)
        }
        return ReadResult(NfcTag(uid.toBigInteger(), sb.toString().ifEmpty { null }), auth.legacy)
    }
```
Beachte: Ein Legacy-Band hat PROT=0, die PIN wäre auch ohne Auth lesbar. Wir lesen trotzdem erst nach erfolgreicher Legacy-Authentifizierung, damit ein Band, das weder das neue noch das alte Passwort kennt, sicher abgelehnt wird.

`cmdPwdAuth` bleibt wie in pretix29 (2-Byte-Antwort, PACK-Vergleich → `TagAuthException`); ein NAK kommt vom Transport als `IOException` (real: `TagLostException`, Subklasse von `IOException`).

Hinweis zum Locked-Fall: Auf echter Hardware ist ein gesperrtes Band vom „falschen Passwort" nicht unterscheidbar (beides NAK). `looksLocked` greift daher nur im Emulator; in der Kasse wird beides als `Auth`-Fehler angezeigt, die Meldung nennt beide Ursachen (Task 5).

- [ ] **Step 4: Push → CI grün**

```bash
git add -A && git commit -m "feat(ntag): mandatory per-band PWD_AUTH with PACK check on read" && git push
```

---

### Task 4: Provisionierung mit Leseschutz, Fehlversuchslimit und Migration

**Files:**
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/nfc/Ntag213.kt` (`provisionTag`, `writeTag`, neue Konstanten, `readStatus`)
- Create (Test): `app/app/src/test/java/de/stustapay/stustapay/ntag/Ntag213ProvisionTest.kt`

**Interfaces:**
- Consumes: `Ntag213Credentials`, `FakeNtag213`
- Produces: `fun Ntag213.provisionTag(pin: String, key0: BitVector, legacy: Ntag213Credentials.Credentials? = Ntag213Credentials.LEGACY)`, `fun Ntag213.writeTag(pin: String, key0: BitVector)`, `data class Ntag213Status(val auth0: Int, val prot: Boolean, val authLim: Int)`, `fun Ntag213.readStatus(key0: BitVector): Ntag213Status`, Konstanten `CFG0_PAGE = 41`, `CFG1_PAGE = 42`, `ACCESS_PROT = 0x80`, `ACCESS_CFGLCK = 0x40`, `ACCESS_AUTHLIM_MASK = 0x07`, `AUTHLIM_VALUE = 3`

- [ ] **Step 1: Failing tests**

```kotlin
package de.stustapay.stustapay.ntag

import de.stustapay.libssp.nfc.Ntag213
import de.stustapay.libssp.nfc.Ntag213Credentials
import de.stustapay.libssp.util.decodeHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ntag213ProvisionTest {
    private val key0 = "000102030405060708090a0b0c0d0e0f".decodeHex()
    private val uid = byteArrayOf(0x04, 0x0E, 0xA8.toByte(), 0x9A.toByte(), 0x33, 0x20, 0x91.toByte())
    private val creds = Ntag213Credentials.derive(key0, uid)
    private fun tag() = FakeNtag213(uid).also { it.connect() }

    private fun assertProvisioned(f: FakeNtag213, pin: String) {
        assertArrayEquals(creds.pwd, f.pwd()); assertArrayEquals(creds.pack, f.pack())
        assertEquals(4, f.auth0()); assertTrue(f.prot()); assertEquals(3, f.authLim())
        assertEquals((f.pages[42][0].toInt() and 0x40), 0) // CFGLCK nie gesetzt
        assertEquals(pin, Ntag213(f).readTag(key0).tag.pin)
    }

    @Test
    fun provision_freshTag() {
        val f = tag(); Ntag213(f).provisionTag("ABCD1234EFGH5678", key0); assertProvisioned(f, "ABCD1234EFGH5678")
    }

    @Test
    fun provision_legacyTag_keepsPin() {
        val f = tag()
        // Legacy-Zustand: PIN "AB12" (kürzer, Nullbytes), PWD 00010203, PACK 0001, AUTH0=4, PROT=0
        f.pages[4] = "AB12".toByteArray(); f.setPwd(Ntag213Credentials.LEGACY.pwd); f.setPack(Ntag213Credentials.LEGACY.pack); f.setAuth0(4)
        Ntag213(f).provisionTag("AB12", key0)
        assertProvisioned(f, "AB12")
    }

    @Test
    fun provision_isIdempotentOnAlreadyNewTag() {
        val f = tag(); Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
        f.close(); f.connect()
        Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
        assertProvisioned(f, "ABCD1234EFGH5678")
    }

    @Test
    fun provision_resumesFromEveryIntermediateState() {
        // Simuliert Abbruch nach jedem Schreibschritt: danach muss ein zweiter Lauf sauber enden.
        for (failAfter in 1..8) {
            val f = tag()
            val flaky = FlakyTransport(f, failAfterWrites = failAfter)
            try { Ntag213(flaky).provisionTag("ABCD1234EFGH5678", key0) } catch (e: java.io.IOException) {}
            f.close(); f.connect()
            Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
            assertProvisioned(f, "ABCD1234EFGH5678")
        }
    }

    @Test
    fun readStatus_reportsProtection() {
        val f = tag(); Ntag213(f).provisionTag("ABCD1234EFGH5678", key0)
        val s = Ntag213(f).readStatus(key0)
        assertEquals(4, s.auth0); assertTrue(s.prot); assertEquals(3, s.authLim)
    }

    @Test
    fun writeTag_rewritesPinOnProvisionedBand() {
        val f = tag(); Ntag213(f).provisionTag("OLDPIN", key0)
        Ntag213(f).writeTag("NEWPIN99", key0)
        assertEquals("NEWPIN99", Ntag213(f).readTag(key0).tag.pin)
        assertFalse(f.pinBytes().contentEquals(ByteArray(16)))
    }
}

/** Wraps a FakeNtag213 and throws IOException on the N-th WRITE (tag pulled away). */
class FlakyTransport(private val inner: FakeNtag213, private val failAfterWrites: Int) : de.stustapay.libssp.nfc.Ntag213Transport {
    private var writes = 0
    override val isConnected get() = inner.isConnected
    override fun connect() = inner.connect()
    override fun close() = inner.close()
    override fun transceive(cmd: ByteArray): ByteArray {
        if ((cmd[0].toInt() and 0xFF) == 0xA2) { writes++; if (writes == failAfterWrites) throw java.io.IOException("tag lost") }
        return inner.transceive(cmd)
    }
}
```

- [ ] **Step 2: Push → CI rot**

- [ ] **Step 3: Implementieren**

Konstanten im `companion object` von `Ntag213` ergänzen (bestehende `AUTH0_PAGE` durch `CFG0_PAGE` ersetzen, Verwendungen anpassen):
```kotlin
        const val CFG0_PAGE = 41           // byte 3 = AUTH0
        const val CFG1_PAGE = 42           // byte 0 = ACCESS: PROT(0x80) CFGLCK(0x40) NFC_CNT_EN(0x10) NFC_CNT_PWD_PROT(0x08) AUTHLIM(0x07)
        const val ACCESS_PROT = 0x80
        const val ACCESS_CFGLCK = 0x40
        const val ACCESS_AUTHLIM_MASK = 0x07
        const val AUTHLIM_VALUE = 3        // datasheet: limit = AUTHLIM or 2^AUTHLIM -> 3 or 8 negative attempts
```
`provisionTag` und `writeTag` ersetzen:
```kotlin
    data class Ntag213Status(val auth0: Int, val prot: Boolean, val authLim: Int)

    /**
     * Provision or migrate a band. Order is chosen so that every interruption leaves a state
     * from which a re-run converges:
     *   1. authenticate with derived creds (already migrated) -> else legacy creds -> else none (fresh)
     *   2. write PIN (pages 4-7)                                     (readable/writable in every start state)
     *   3. write PWD (43) + PACK (44)                                 (from now on only derived creds work)
     *   4. CFG0: AUTH0 = 4                                            (writes >= 4 need auth; we are authenticated or fresh)
     *   5. CFG1: PROT = 1, AUTHLIM = 3, CFGLCK untouched              (reads >= 4 need auth)
     * A re-run after step 3 succeeds via derived auth; before step 3 via legacy/no auth.
     */
    fun provisionTag(pin: String, key0: BitVector, legacy: Ntag213Credentials.Credentials? = Ntag213Credentials.LEGACY) {
        if (!isConnected) throw TagConnectionException()
        val creds = Ntag213Credentials.derive(key0, readUidBytes())

        // fresh band: no auth needed (AUTH0 = 0xFF). If neither derived nor legacy auth works and the
        // band is protected, the writes below NAK and the caller reports Auth failure (foreign band).
        tryAuth(creds) || (legacy != null && tryAuth(legacy))

        writePin(pin)
        cmdWrite(PWD_PAGE.toUByte(), creds.pwd[0].toUByte(), creds.pwd[1].toUByte(), creds.pwd[2].toUByte(), creds.pwd[3].toUByte())
        cmdWrite(PACK_PAGE.toUByte(), creds.pack[0].toUByte(), creds.pack[1].toUByte(), 0x00u, 0x00u)
        // (fresh band, !authed: still allowed — AUTH0 is 0xFF until we set it below)
        val cfg0 = cmdRead(CFG0_PAGE.toUByte())
        cmdWrite(CFG0_PAGE.toUByte(), cfg0[0].toUByte(), cfg0[1].toUByte(), cfg0[2].toUByte(), PIN_PAGE_START.toUByte())
        val cfg1 = cmdRead(CFG1_PAGE.toUByte())
        // keep NFC_CNT_EN / NFC_CNT_PWD_PROT bits, never set CFGLCK, set PROT, set AUTHLIM
        val keepMask = (ACCESS_PROT or ACCESS_CFGLCK or ACCESS_AUTHLIM_MASK).inv() and 0xFF
        val newAccess = ((cfg1[0].toInt() and keepMask) or ACCESS_PROT or AUTHLIM_VALUE) and 0xFF
        cmdWrite(CFG1_PAGE.toUByte(), newAccess.toUByte(), cfg1[1].toUByte(), cfg1[2].toUByte(), cfg1[3].toUByte())
    }

    /** Rewrite the PIN on an already provisioned band (chip_debug "Rewrite"). */
    fun writeTag(pin: String, key0: BitVector) {
        authenticate(key0)
        writePin(pin)
    }

    fun readStatus(key0: BitVector): Ntag213Status {
        authenticate(key0)
        val cfg0 = cmdRead(CFG0_PAGE.toUByte()); val cfg1 = cmdRead(CFG1_PAGE.toUByte())
        val access = cfg1[0].toInt() and 0xFF
        return Ntag213Status(cfg0[3].toInt() and 0xFF, (access and ACCESS_PROT) != 0, access and ACCESS_AUTHLIM_MASK)
    }

    private fun tryAuth(c: Ntag213Credentials.Credentials): Boolean =
        try { cmdPwdAuth(c.pwd, c.pack); true } catch (e: Exception) { false }

    private fun writePin(pin: String) {
        val pinBytes = ByteArray(PIN_MAX_LENGTH)
        pin.toByteArray(Charsets.US_ASCII).copyInto(pinBytes, endIndex = minOf(pin.length, PIN_MAX_LENGTH))
        for (page in 0 until 4) {
            val o = page * 4
            cmdWrite((PIN_PAGE_START + page).toUByte(), pinBytes[o].toUByte(), pinBytes[o + 1].toUByte(), pinBytes[o + 2].toUByte(), pinBytes[o + 3].toUByte())
        }
    }
```
Anmerkung zum Fehlerbild „Migration von Legacy-Band mit PROT=0": Schritt 1 authentifiziert mit Legacy-PWD (nötig, weil AUTH0=4 Schreibzugriffe ab Seite 4 schützt); danach laufen Schritte 2–5 authentifiziert.

- [ ] **Step 4: Push → CI grün**

```bash
git add -A && git commit -m "feat(ntag): provisioning with PROT/AUTHLIM, per-band creds, legacy migration, status read" && git push
```

---

### Task 5: NfcHandler und Kassen-App auf den neuen Pfad umstellen

**Files:**
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/nfc/NfcHandler.kt:158-190` (`handleNtag213Tag`) und Catch-Block `:100-114`
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/model/NfcScanFailure.kt` (neuer Fall `Locked`)
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/model/NfcScanResult.kt` (`Read` bekommt `val legacy: Boolean = false`)
- Modify: `app/app/src/main/java/de/stustapay/stustapay/ui/chipscan/*` nur falls `NfcScanFailure` dort exhaustiv gematcht wird (`grep -rn "is NfcScanFailure" app/app app/chip_debug`)

**Interfaces:**
- Consumes: `Ntag213.readTag(key0)`, `provisionTag(pin, key0)`, `writeTag(pin, key0)`, `TagLockedException`
- Produces: `NfcScanFailure.Locked(msg: String)`; NTAG-Pfad nutzt ausschließlich `req.dataProtKey` (= key0); `uidRetrKey` wird für NTAG ignoriert

- [ ] **Step 1: `handleNtag213Tag` ersetzen**

```kotlin
    private fun handleNtag213Tag(tag: Ntag213) {
        val req = dataSource.getScanRequest() ?: return
        tag.connect()
        when (req) {
            is NfcScanRequest.Read -> {
                val key0 = req.dataProtKey ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.NoKey)); return
                }
                val r = tag.readTag(key0)
                if (r.legacy) Log.w("NfcHandler", "legacy NTAG213 band (uid ${r.tag.uid.toString(16).take(6)}…) — bitte neu provisionieren")
                dataSource.setScanResult(NfcScanResult.Read(r.tag, legacy = r.legacy))
            }
            is NfcScanRequest.Write -> {
                val key0 = req.dataProtKey ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.NoKey)); return
                }
                val pin = req.pin ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("PIN required for NTAG213"))); return
                }
                tag.provisionTag(pin, key0)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Rewrite -> {
                val ser = tag.readUid()
                val pin = uid_map[ser] ?: run {
                    dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("UID not found"))); return
                }
                tag.writeTag(pin, req.dataProtKey)
                dataSource.setScanResult(NfcScanResult.Write)
            }
            is NfcScanRequest.Test -> {
                dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Other("Test not supported for NTAG")))
            }
        }
    }
```
Im Catch-Block von `handleTag` vor `TagAuthException` ergänzen:
```kotlin
        } catch (e: TagLockedException) {
            dataSource.setScanResult(NfcScanResult.Fail(NfcScanFailure.Locked("Band gesperrt — bitte an der Kasse tauschen")))
```
und die Auth-Meldung ändern in `"Band nicht für dieses Event provisioniert oder gesperrt"`.

- [ ] **Step 2: `NfcScanFailure.Locked` ergänzen** — in `NfcScanFailure.kt` neben `Auth`: `data class Locked(val msg: String) : NfcScanFailure`. Alle `when (failure)`-Blöcke, die exhaustiv sind, um `is NfcScanFailure.Locked -> …` (Text der Meldung anzeigen) ergänzen — Fundstellen per `grep -rn "NfcScanFailure.Auth" app/`.

- [ ] **Step 3: Kassen-App prüfen** — `app/app/.../repository/NfcRepository.kt` liefert `dataProtKey = key0` bereits über `setTagKeys`; keine Änderung. `grep -rn "readTag(" app/` darf nur noch die neue Signatur zeigen.

- [ ] **Step 4: Push → CI grün (build + test)**

```bash
git add -A && git commit -m "feat(ntag): NfcHandler uses per-band auth; Locked failure surfaced" && git push
```

---

### Task 6: Chip-Debug-App: Event-Schlüssel eingeben statt Testschlüssel

**Files:**
- Modify: `app/chip_debug/build.gradle` (Dependency `androidx.security:security-crypto:1.1.0-alpha06`)
- Create: `app/chip_debug/src/main/java/de/stustapay/chip_debug/repository/KeyRepository.kt`
- Modify: `app/chip_debug/src/main/java/de/stustapay/chip_debug/repository/NfcRepository.kt` (Schlüssel aus `KeyRepository`, kein Default)
- Create: `app/chip_debug/src/main/java/de/stustapay/chip_debug/ui/key/KeyView.kt`, `KeyViewModel.kt`
- Modify: `app/chip_debug/src/main/java/de/stustapay/chip_debug/ui/root/RootNavDests.kt`, `ui/root/StartpageItems.kt`, `ui/nav/NavDestinations.kt` (Eintrag „Schlüssel", Route `key`)
- Create (Test): `app/app/src/test/java/de/stustapay/stustapay/ntag/KeyRepositoryTest.kt` (nur die reine Hex-Validierung, siehe `parseKey0`)

**Interfaces:**
- Produces: `class KeyRepository(context: Context) { val key0: StateFlow<BitVector?>; fun setKey0Hex(hex: String): Boolean; fun clear(); companion object { fun parseKey0(hex: String): BitVector? } }` — `parseKey0` akzeptiert 32 Hex-Zeichen (Groß/Klein, Leerzeichen/Doppelpunkte erlaubt), sonst `null`.
- `NfcRepository.write/read/rewrite/test` liefern `NfcScanResult.Fail(NfcScanFailure.NoKey)`, wenn `key0 == null`.

- [ ] **Step 1: Failing test für `parseKey0`**

```kotlin
package de.stustapay.stustapay.ntag

import de.stustapay.chip_debug.repository.KeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyRepositoryTest {
    @Test fun parse_acceptsPlainHex() { assertEquals(128uL, KeyRepository.parseKey0("000102030405060708090a0b0c0d0e0f")!!.len) }
    @Test fun parse_acceptsSeparators() { assertEquals(128uL, KeyRepository.parseKey0("00:01:02:03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F")!!.len) }
    @Test fun parse_rejectsWrongLength() { assertNull(KeyRepository.parseKey0("0001")) }
    @Test fun parse_rejectsNonHex() { assertNull(KeyRepository.parseKey0("zz0102030405060708090a0b0c0d0e0f")) }
}
```
Hinweis: `app/app/build.gradle` braucht dafür `testImplementation project(':chip_debug')` — falls das zyklisch ist, `parseKey0` stattdessen nach `libssp/util/KeyParsing.kt` legen (`object KeyParsing { fun parseKey0(hex: String): BitVector? }`) und aus `KeyRepository` aufrufen; Test dann gegen `KeyParsing`.

- [ ] **Step 2: Push → CI rot**

- [ ] **Step 3: Implementieren**

`KeyRepository.kt`:
```kotlin
package de.stustapay.chip_debug.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import de.stustapay.libssp.util.BitVector
import de.stustapay.libssp.util.decodeHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "chip_debug_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _key0 = MutableStateFlow(prefs.getString("key0", null)?.let { parseKey0(it) })
    val key0: StateFlow<BitVector?> = _key0

    /** Returns false (and stores nothing) if the input is not 16 bytes of hex. */
    fun setKey0Hex(hex: String): Boolean {
        val k = parseKey0(hex) ?: return false
        prefs.edit().putString("key0", normalize(hex)).apply()
        _key0.value = k
        return true
    }

    fun clear() { prefs.edit().remove("key0").apply(); _key0.value = null }

    companion object {
        private fun normalize(hex: String) = hex.filter { it.isLetterOrDigit() }.lowercase()
        fun parseKey0(hex: String): BitVector? {
            val n = normalize(hex)
            if (n.length != 32 || !n.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return n.decodeHex()
        }
    }
}
```
`NfcRepository.kt` (chip_debug): die drei `MutableStateFlow(... "000102…".decodeHex())` entfernen; Konstruktor `@Inject constructor(private val nfcDataSource: NfcDataSource, private val keys: KeyRepository)`; in jeder Methode `val k = keys.key0.value ?: return NfcScanResult.Fail(NfcScanFailure.NoKey)` und `NfcScanRequest.Read(uidRetrKey = k, dataProtKey = k)` bzw. `Write(uidRetrKey = k, dataProtKey = k, pin = pin)`, `Rewrite(k, k, k)`, `Test(k, k)` (für NTAG zählt nur `dataProtKey`; für MF0AES bleibt das Verhalten „ein Schlüssel für beides" wie bisher).

`KeyViewModel.kt` / `KeyView.kt`: ein `OutlinedTextField` (Monospace, 32 Zeichen, `visualTransformation = PasswordVisualTransformation()` mit Auge-Toggle), Button „Speichern" (ruft `setKey0Hex`, zeigt bei `false` „Ungültig: 32 Hex-Zeichen erwartet"), Button „Löschen", Statuszeile „Schlüssel gesetzt: ja/nein (Fingerprint ab12…)" — Fingerprint = erste 4 Hex-Zeichen von SHA-256 des Schlüssels, nie der Schlüssel selbst. Navigation: in `RootNavDests` `val key = NavDest("key")`, in `StartpageItems` Eintrag „Schlüssel" mit `de.stustapay.libssp.R.drawable.key_24` (falls kein Key-Icon in libssp: `settings_24` verwenden).

- [ ] **Step 4: Push → CI grün**

```bash
git add -A && git commit -m "feat(chip_debug): event key entry (encrypted prefs) instead of hardcoded debug key" && git push
```

---

### Task 7: Chip-Debug „Prüfen" zeigt den Schutzstatus

**Files:**
- Modify: `app/chip_debug/src/main/java/de/stustapay/chip_debug/ui/verify/NfcVerifyViewModel.kt`, `NfcVerifyView.kt`
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/model/NfcScanRequest.kt`, `NfcScanResult.kt` (neuer Request `Status`, neues Result `Status`)
- Modify: `app/libssp/src/main/java/de/stustapay/libssp/nfc/NfcHandler.kt` (`handleNtag213Tag`: `is NfcScanRequest.Status`; `handleMfUlAesTag`: `Status` → `Fail(Other("nur NTAG"))`)
- Modify: `app/chip_debug/src/main/java/de/stustapay/chip_debug/repository/NfcRepository.kt` (`suspend fun status()`)

**Interfaces:**
- Produces: `NfcScanRequest.Status(val dataProtKey: BitVector)`, `NfcScanResult.Status(val uid: ULong, val pin: String?, val auth0: Int, val prot: Boolean, val authLim: Int)`
- Consumes: `Ntag213.readStatus(key0)`, `Ntag213.readTag(key0)`

- [ ] **Step 1: Request/Result ergänzen** (in den `sealed interface`s):
```kotlin
    data class Status(val dataProtKey: BitVector) : NfcScanRequest
```
```kotlin
    data class Status(val uid: ULong, val pin: String?, val auth0: Int, val prot: Boolean, val authLim: Int, val legacy: Boolean) : NfcScanResult
```
Alle exhaustiven `when` über `NfcScanRequest`/`NfcScanResult` erweitern (`grep -rn "is NfcScanResult.Test" app/`).

- [ ] **Step 2: Handler**: in `handleNtag213Tag`:
```kotlin
            is NfcScanRequest.Status -> {
                val r = tag.readTag(req.dataProtKey)
                val s = tag.readStatus(req.dataProtKey)
                dataSource.setScanResult(NfcScanResult.Status(r.tag.uid.longValue().toULong(), r.tag.pin, s.auth0, s.prot, s.authLim, r.legacy))
            }
```
(`NfcTag.uid` ist `BigInteger` aus `com.ionspin.kotlin.bignum` — `longValue()` existiert dort; sonst `t.uid.toString().toULong()`.)

- [ ] **Step 3: Verify-Screen**: nach Scan anzeigen — UID (hex), PIN vorhanden ja/nein (nie die PIN selbst), „Leseschutz: AN/AUS", „Fehlversuchslimit: 3/aus", „AUTH0: 4". Alles grün, wenn `!legacy && prot && authLim == 3 && auth0 == 4`; sonst rote Zeile „Band veraltet — bitte neu provisionieren". Bei `NfcScanFailure.Auth`: „Band gehört nicht zu diesem Schlüssel (oder Legacy) — Provisionieren migriert es".

- [ ] **Step 4: Push → CI grün**

```bash
git add -A && git commit -m "feat(chip_debug): verify screen shows NTAG protection status" && git push
```

---

### Task 8: Doku, Gerätetest, Release und Band-Migration

**Files:**
- Create: `docs/ntag213-security.md` (Konzept: Ableitung, Speicherlayout, Migration, Grenzen von NTAG213)
- Modify: `debian/changelog` NICHT (Versionsstring bleibt), Tag `v2026.2.1-pretix30-rc1` → Test → `v2026.2.1-pretix30`

- [ ] **Step 1: `docs/ntag213-security.md` schreiben** — Inhalt: (a) Warum: Audit-Befund; (b) Ableitung `PWD||PACK = AES-CMAC(key0, "NTAG213-PWD"||UID)[0..5]`; (c) Konfiguration AUTH0=4, PROT=1, AUTHLIM=3, CFGLCK nie; (d) Migration Legacy→neu über Chip-Debug „Provisionieren" (PIN wird aus dem Band gelesen? NEIN — die PIN muss der Admin aus der Chip-Liste kennen; alternativ Verify-Screen liest die PIN mit Legacy-Auth nicht mehr aus → dokumentieren: Migration = Band auflegen, Provisionieren mit der bekannten PIN aus der Admin-Chip-Liste; Rewrite (uid_map) setzt PIN aus der Liste); (e) Grenzen: 32-Bit-Passwort im Klartext über Funk, kein Schutz gegen Sniffing an der Kasse; für höhere Anforderungen MIFARE Ultralight AES; (f) Was bei verlorenem key0 passiert (Bänder nicht mehr provisionierbar → Bänder tauschen).

- [ ] **Step 2: RC taggen und CI abwarten**
```bash
git tag -a v2026.2.1-pretix30-rc1 -m "RC1 NTAG213 security" && git push origin v2026.2.1-pretix30-rc1
gh run watch --repo SpLord/stustapay $(gh run list --repo SpLord/stustapay --limit 1 --json databaseId --jq '.[0].databaseId')
```
Erwartung: alle Jobs grün, Release als Pre-Release (rc).

- [ ] **Step 3: Gerätetest (manuell, Checkliste — Ergebnisse in `docs/ntag213-security.md` unter „Testprotokoll" eintragen)**
  1. Chip-Debug-APK (rc1) auf ein Terminal, Schlüssel = `key0` des Events (Admin → Node → User-Tag-Secrets) eintragen.
  2. Frisches NTAG213: Provisionieren mit PIN `TEST1234` → Verify: Leseschutz AN, Limit 3, AUTH0 4.
  3. Dasselbe Band mit fremdem Handy (NFC Tools): Seiten 4–7 dürfen NICHT lesbar sein; PWD_AUTH mit `00010203` muss NAK liefern.
  4. Legacy-Band (bisher provisioniert): Provisionieren mit seiner PIN → Verify grün → Kasse (pretix30-rc1-App) liest UID+PIN, Verkauf/Login funktioniert.
  5. Kasse mit anderem Event-Schlüssel (zweites Test-Event) → Meldung „nicht für dieses Event provisioniert".
  6. MF0AES-Band (Upstream) → weiterhin AES-Pfad, Verkauf funktioniert.
  7. Fehlversuchslimit: Chip-Debug mit falschem Schlüssel 3× (bzw. 8×) scannen, danach mit richtigem Schlüssel → gesperrt? Ergebnis dokumentieren (klärt `AUTHLIM` vs `2^AUTHLIM`). Dieses Band danach entsorgen.
  8. Timing: Scan-Dauer an der Kasse ≤ 300 ms (ein GET_VERSION + READ + PWD_AUTH + READ mehr als bisher).

- [ ] **Step 4: Finales Release + Migration**

Nachlauf (eigener Task nach dem Event, pretix31): `ACCEPT_LEGACY_BANDS = false` setzen, Test `readTag_legacyBand_isAcceptedButFlagged` auf `TagAuthException` umdrehen, Release.
```bash
git tag -a v2026.2.1-pretix30 -m "NTAG213: per-band password, read protection, auth limit; chip_debug key entry" && git push origin v2026.2.1-pretix30
```
Reihenfolge ist dank Übergangsmodus frei: die Kassen-App darf vor, während oder nach der Band-Migration auf pretix30 kommen. Migrationsablauf für alle vorhandenen Bänder (vor dem nächsten Event, ~5 s pro Band): Chip-Debug → Schlüssel eintragen → Provisionieren mit der PIN aus der Admin-Chip-Liste, oder „Rewrite" (nutzt `uid_map`) → Verify grün. Kassen-App auf pretix30 ziehen (Auto-Update oder Neuinstallation, falls der Keystore vorher rotiert wurde).

---

## Self-Review

- **Spec-Abdeckung:** K5 (PROT, AUTHLIM, Passwort pro Band, PACK-Prüfung) → Tasks 2–4; W6 (PWD_AUTH-Antwortlänge) bereits in pretix29, PACK-Pflicht in Task 3; W7 (Rewrite) → Task 4/5; hartcodierter Debug-Schlüssel → Task 6; Nachweis am Gerät → Task 8. Backend unverändert (Constraint).
- **Platzhalter:** keine; alle Code-Schritte enthalten Code. Die Verify-UI (Task 7 Step 3) und Key-UI (Task 6 Step 3) sind als Anforderungsliste beschrieben, weil das Compose-Layout an die bestehenden Screens (`NfcVerifyView`, `NfcWriteView`) angelehnt werden soll — Implementierer kopiert deren Struktur.
- **Typkonsistenz:** `Ntag213Credentials.Credentials(pwd, pack)`, `derive(key0: BitVector, uid: ByteArray)`, `readTag(key0)`, `provisionTag(pin, key0, legacy)`, `writeTag(pin, key0)`, `readStatus(key0): Ntag213Status`, `FakeNtag213.provisionNew(key0, pin)`, `NfcScanFailure.Locked`, `NfcScanRequest.Status`, `NfcScanResult.Status` — durchgängig gleich benannt.
- **Review Focus:** 1 → Task 3 `readTag_wrongKey_throwsAuth`; 2 → Task 4 `provision_resumesFromEveryIntermediateState`; 3 → Task 3 `readTag_lockedTag_reportsLocked` (+ Hinweis Hardware-Grenze); 4 → Task 6 (NoKey-Pfad; Test über `parseKey0` + Repository-Verhalten in der CI-Kompilierung — ergänzend manuell in Task 8 Schritt 1); 5 → Task 4 `provision_legacyTag_keepsPin`.
