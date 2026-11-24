# ETG — Export To Graphene

**ETG (Export To Graphene)** is a 100% FOSS, offline, zero-Google-Services Android migration application designed to export your personal data from a stock Android phone and cleanly restore it onto **GrapheneOS** (with an authentic Obsidian/Carbon GrapheneOS UI).

---

## Co przenosi ETG? / What ETG Migrates

1. **Kontakty / Contacts** — `contacts/contacts.vcf` (vCard 3.0 UTF-8) + `contacts.json` + HD zdjęcia kontaktów; przywracane lokalnie na GrapheneOS bez konta Google.
2. **Wiadomości SMS i MMS / SMS & MMS** — wątki SMS, metadane MMS oraz załączniki (`messages/sms_mms.json` + standardowy `messages/sms_backup.xml`). Przywracanie przez tymczasową rolę domyślnej aplikacji SMS (`RoleManager.ROLE_SMS`).
3. **Historia połączeń / Call Log** — połączenia przychodzące, wychodzące, nieodebrane i odrzucone.
4. **Komunikatory i historia czatów / Messengers & Chat Backups** — wykrywa Signal, Molly, WhatsApp, Telegram, Element, Session, SimpleX; eksportuje lokalne kopie zapasowe (`Android/media/com.whatsapp/WhatsApp/...`, `Signal/Backups/...` oraz wybrane foldery SAF) z zachowaniem oryginalnej struktury ścieżek.
5. **Kalendarz / Calendar & Events** — pliki `.ics` (RFC 5545) + `calendar.json` (powtarzanie `RRULE` + przypomnienia `VALARM`). Na świeżym GrapheneOS automatycznie tworzy lokalny kalendarz offline (`ETG Local Calendar`).
6. **Zdjęcia, wideo i audio / Photos, Videos & Audio** — pełne zachowanie albumów/ścieżek `MediaStore`, opcjonalne pominięcie wideo oraz wykrywanie duplikatów.
7. **Dokumenty i pobrane / Documents & Downloads** — pliki PDF/biurowe oraz własne katalogi użytkownika (SAF / All Files).
8. **Sejf, klucze i 2FA / Vault, Keys & 2FA** — automatyczne wykrywanie baz KeePass (`.kdbx`), eksportów Aegis/2FAS/FreeOTP, konfiguracji WireGuard/OpenVPN, kluczy SSH i zakładek HTML.
9. **Połączone sieci Wi-Fi / Wi-Fi Networks** — wbudowany skaner kodów QR „Udostępnij Wi-Fi”, dodawanie ręczne, parser `WifiConfigStore.xml`, opcjonalny odczyt root (`su`) oraz zapis na GrapheneOS 1 kliknięciem przez `ACTION_WIFI_ADD_NETWORKS`.
10. **Aplikacje i pakiety APK / Apps & APKs** — manifest zainstalowanych aplikacji, rekomendacje zamienników FOSS (F-Droid, Accrescent, Obtainium, Aurora Store), audyt aplikacji bankowych / Google Play Integrity oraz opcjonalny eksport i instalacja plików `base.apk` + Split-APK.
11. **Ustawienia systemowe i audyt gotowości GrapheneOS / System Settings & Audit** — ustawienia ekranu, prywatny DNS, słownik użytkownika, sparowane Bluetooth, tapeta oraz lista kontrolna migracji krok po kroku.

---

## 4 Tryby Transferu / 4 Transport Modes

1. **Plik lokalny (`.etg` / `.etg.zip`)** — zapis na pamięć urządzenia z szyfrowaniem **AES-256-GCM + PBKDF2-SHA256 (210 000 iteracji)** i sumami kontrolnymi SHA-256.
2. **Pendrive / Dysk USB-OTG** — bezpośredni zapis na pamięć USB (z automatycznym ostrzeżeniem dla limitu 4 GB FAT32 i trybem rozpakowanego folderu USB).
3. **Kablowo (USB-C / ADB)** — bezpośredni transfer po kablu przez Tethering USB-C (`usb0`/`rndis0`) lub przez komputer z `adb forward tcp:47821 tcp:47821` oraz wbudowany serwer HTTP z kodem PIN dla przeglądarki PC (`adb forward tcp:47822 tcp:47822`).
4. **Bezprzewodowo (Wi-Fi / P2P / QR)** — bezpośredni transfer TCP szyfrowany sesyjnie z 6-cyfrowym kodem PIN, parowaniem kodem QR, automatycznym wykrywaniem mDNS/NSD w sieci LAN/Hotspot oraz obsługą Wi-Fi Direct bez routera.

---

## Build Instructions (Windows / Linux / macOS)

Requires **JDK 21** (e.g. Android Studio JBR) and Android SDK (`compileSdk = 37`, `minSdk = 26`, `targetSdk = 36`):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

---

## Licencja / License

Copyright (C) 2026 Błażej Stosio

ETG jest wolnym oprogramowaniem na licencji **GNU Affero General Public License v3.0** – szczegóły w pliku [LICENSE](LICENSE).

ETG is free software licensed under the **GNU Affero General Public License v3.0** – see [LICENSE](LICENSE). You can redistribute and/or modify it under the terms of the AGPL, version 3 only.
