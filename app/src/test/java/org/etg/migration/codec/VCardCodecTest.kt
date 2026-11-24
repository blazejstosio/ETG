package org.etg.migration.codec

import org.etg.migration.model.ContactAddress
import org.etg.migration.model.ContactEmail
import org.etg.migration.model.ContactEvent
import org.etg.migration.model.ContactPhone
import org.etg.migration.model.ContactRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VCardCodecTest {
    @Test
    fun roundTripPolishUtf8AndPhotoAndFolding() {
        val fakePhoto = ByteArray(180) { (it % 127).toByte() }
        val photoB64 = Base64.getEncoder().encodeToString(fakePhoto)

        val original = listOf(
            ContactRecord(
                displayName = "Żółć Źrebak Gęś Jaźń",
                givenName = "Żółć",
                familyName = "Źrebak",
                middleName = "Jan",
                prefix = "dr",
                suffix = "inż.",
                organization = "Politechnika; Wrocław",
                jobTitle = "Inżynier Bezpieczeństwa",
                note = "Linia pierwsza\nLinia druga, z przecinkiem; i średnikiem",
                phones = listOf(
                    ContactPhone("+48 600 123 456", type = 2),
                    ContactPhone("22 555 00 11", type = 3)
                ),
                emails = listOf(
                    ContactEmail("zolc@example.pl", type = 1),
                    ContactEmail("biuro@firma.pl", type = 2)
                ),
                addresses = listOf(
                    ContactAddress(
                        street = "ul. Łąkowa 12/4",
                        city = "Łódź",
                        region = "Łódzkie",
                        postalCode = "90-001",
                        country = "Polska",
                        type = 1
                    )
                ),
                events = listOf(ContactEvent("1994-05-18", type = 3)),
                websites = listOf("https://grapheneos.org"),
                groups = listOf("Rodzina", "Praca"),
                photoBase64 = photoB64
            )
        )

        val vcf = VCardCodec.encodeAll(original)
        assertTrue(vcf.contains("BEGIN:VCARD"))
        assertTrue(vcf.contains("VERSION:3.0"))

        val decoded = VCardCodec.decodeAll(vcf)
        assertEquals(1, decoded.size)
        val c = decoded[0]
        assertEquals("Żółć Źrebak Gęś Jaźń", c.displayName)
        assertEquals("Żółć", c.givenName)
        assertEquals("Źrebak", c.familyName)
        assertEquals("Jan", c.middleName)
        assertEquals("dr", c.prefix)
        assertEquals("inż.", c.suffix)
        assertEquals("Inżynier Bezpieczeństwa", c.jobTitle)
        assertEquals("Linia pierwsza\nLinia druga, z przecinkiem; i średnikiem", c.note)
        assertEquals(2, c.phones.size)
        assertEquals("+48 600 123 456", c.phones[0].number)
        assertEquals(2, c.emails.size)
        assertEquals("zolc@example.pl", c.emails[0].address)
        assertEquals(1, c.addresses.size)
        assertEquals("Łódź", c.addresses[0].city)
        assertEquals("1994-05-18", c.events[0].startDate)
        assertEquals(listOf("https://grapheneos.org"), c.websites)
        assertEquals(listOf("Rodzina", "Praca"), c.groups)
        assertEquals(photoB64, c.photoBase64)
    }
}
