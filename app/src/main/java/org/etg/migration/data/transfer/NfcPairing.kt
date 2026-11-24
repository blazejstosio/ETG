package org.etg.migration.data.transfer

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import java.nio.charset.StandardCharsets

object NfcPairing {
    private val AID = byteArrayOf(0xF0.toByte(), 0x45, 0x54, 0x47, 0x50, 0x41, 0x49, 0x52)
    private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    private val SW_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())

    @Volatile
    var activePayload: String? = null

    enum class Availability { AVAILABLE, DISABLED, UNSUPPORTED }

    fun availability(context: Context): Availability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return Availability.UNSUPPORTED
        return if (adapter.isEnabled) Availability.AVAILABLE else Availability.DISABLED
    }

    fun selectApdu(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    internal fun respond(apdu: ByteArray?): ByteArray {
        if (apdu == null || !apdu.contentEquals(selectApdu()) && !isSelectForAid(apdu)) return SW_NOT_FOUND
        val payload = activePayload ?: return SW_NOT_FOUND
        return payload.toByteArray(StandardCharsets.UTF_8) + SW_OK
    }

    private fun isSelectForAid(apdu: ByteArray): Boolean =
        apdu.size >= 5 + AID.size &&
            apdu[1] == 0xA4.toByte() &&
            apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)

    fun startReader(activity: Activity, onPayload: (String) -> Unit): () -> Unit {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return {}
        val callback = NfcAdapter.ReaderCallback { tag: Tag ->
            val iso = IsoDep.get(tag) ?: return@ReaderCallback
            runCatching {
                iso.use {
                    it.connect()
                    it.timeout = 3000
                    val resp = it.transceive(selectApdu())
                    if (resp.size > 2 &&
                        resp[resp.size - 2] == SW_OK[0] &&
                        resp[resp.size - 1] == SW_OK[1]
                    ) {
                        onPayload(String(resp, 0, resp.size - 2, StandardCharsets.UTF_8))
                    }
                }
            }
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        runCatching {
            adapter.enableReaderMode(activity, callback, flags, Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
            })
        }
        return { runCatching { adapter.disableReaderMode(activity) } }
    }
}

class NfcPairingService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        NfcPairing.respond(commandApdu)

    override fun onDeactivated(reason: Int) = Unit
}
