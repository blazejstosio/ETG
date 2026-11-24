package org.etg.migration.codec

import org.etg.migration.model.WifiNetworkEntry
import org.etg.migration.model.WifiSecurityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrAndXmlParserTest {
    @Test
    fun parseAndRoundTripWifiQrWithEscaping() {
        val entry = WifiNetworkEntry(
            ssid = "Domowa;Sieć:5G",
            password = "Hasło\\z;znakami:123",
            securityType = WifiSecurityType.WPA3,
            hidden = true
        )
        val qr = WifiQrAndXmlParser.toWifiQrString(entry)
        val parsed = WifiQrAndXmlParser.parseWifiQr(qr)

        assertNotNull(parsed)
        assertEquals("Domowa;Sieć:5G", parsed?.ssid)
        assertEquals("Hasło\\z;znakami:123", parsed?.password)
        assertEquals(WifiSecurityType.WPA3, parsed?.securityType)
        assertTrue(parsed?.hidden == true)
    }

    @Test
    fun parseOpenWifiAndRejectGarbage() {
        val open = WifiQrAndXmlParser.parseWifiQr("WIFI:T:nopass;S:GuestNet;;")
        assertNotNull(open)
        assertEquals("GuestNet", open?.ssid)
        assertEquals("", open?.password)
        assertEquals(WifiSecurityType.OPEN, open?.securityType)

        assertNull(WifiQrAndXmlParser.parseWifiQr("https://example.com"))
        assertNull(WifiQrAndXmlParser.parseWifiQr("WIFI:T:WPA;;"))
    }

    @Test
    fun parseWifiConfigStoreXmlModernAndLegacy() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <WifiConfigStoreData>
              <NetworkList>
                <Network>
                  <WifiConfiguration>
                    <string name="ConfigKey">"Dom_5G"WPA_PSK</string>
                    <string name="SSID">"Dom_5G"</string>
                    <string name="PreSharedKey">"TajneHaslo2026"</string>
                    <boolean name="HiddenSSID" value="false" />
                  </WifiConfiguration>
                  <SecurityParamsList>
                    <SecurityParams>
                      <int name="SecurityType" value="2" />
                    </SecurityParams>
                  </SecurityParamsList>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="ConfigKey">"Biuro_WPA3"SAE</string>
                    <string name="SSID">"Biuro_WPA3"</string>
                    <string name="PreSharedKey">"Wpa3StrongKey!"</string>
                    <boolean name="HiddenSSID" value="true" />
                  </WifiConfiguration>
                  <SecurityParamsList>
                    <SecurityParams>
                      <int name="SecurityType" value="4" />
                    </SecurityParams>
                  </SecurityParamsList>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="ConfigKey">"Lotnisko_Free"NONE</string>
                    <string name="SSID">"Lotnisko_Free"</string>
                    <null name="PreSharedKey" />
                  </WifiConfiguration>
                </Network>
              </NetworkList>
            </WifiConfigStoreData>
        """.trimIndent()

        val networks = WifiQrAndXmlParser.parseWifiConfigStoreXml(xml)
        assertEquals(3, networks.size)

        assertEquals("Dom_5G", networks[0].ssid)
        assertEquals("TajneHaslo2026", networks[0].password)
        assertEquals(WifiSecurityType.WPA2, networks[0].securityType)

        assertEquals("Biuro_WPA3", networks[1].ssid)
        assertEquals("Wpa3StrongKey!", networks[1].password)
        assertEquals(WifiSecurityType.WPA3, networks[1].securityType)
        assertTrue(networks[1].hidden)

        assertEquals("Lotnisko_Free", networks[2].ssid)
        assertEquals("", networks[2].password)
        assertEquals(WifiSecurityType.OPEN, networks[2].securityType)
    }
}
