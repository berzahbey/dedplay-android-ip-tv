# IPTV Player (Android TV) — Kurulum Rehberi

Bu uygulama sadece bir **oynatıcıdır**. İçerik, sizin kendi (yasal) M3U linkinizden veya Xtream hesabınızdan gelir.

> **Önemli:** Uygulama Android TV / Google TV işletim sistemi olan cihazlarda çalışır (Android TV kutusu, Mi Box, Chromecast with Google TV, Android TV'li televizyon). Samsung (Tizen) veya LG (webOS) televizyonların kendi sistemine APK kurulamaz; onlar için Android TV kutusu/çubuğu gerekir.

---

## 1. Bilgisayara Android Studio kurun (bir kez)

1. https://developer.android.com/studio adresinden **Android Studio**'yu indirip kurun (hep "Next" deyin).
2. İlk açılışta "Standard" kurulumu seçin; gerekli dosyaları kendisi indirir (10-20 dk sürebilir).

## 2. Projeyi açın

1. `IPTVPlayer` klasörünü (zip'ten çıkardıktan sonra) bilgisayarınıza koyun.
2. Android Studio > **Open** > `IPTVPlayer` klasörünü seçin.
3. Altta "Gradle sync" çalışır; **ilk seferde 5-15 dakika** sürer, bekleyin.
4. Eğer "Update Android Gradle Plugin?" benzeri bir öneri çıkarsa **önce "Don't remind / Hayır"** deyin; bu sürümle çalışır.
5. Sync hata verirse en alttaki hata satırını bana aynen gönderin, düzeltirim.

## 3. APK dosyasını üretin

Menüden: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
Bitince sağ altta "locate" yazar; dosya şurada olur:
`IPTVPlayer/app/build/outputs/apk/debug/app-debug.apk`

## 4. TV'ye kurun (iki yoldan biri)

### Yol A — USB bellek (en kolayı)
1. `app-debug.apk` dosyasını USB belleğe kopyalayın, belleği TV kutusuna takın.
2. TV'de bir dosya yöneticisi (örn. "File Commander" / "X-plore") kurun, APK'ya girip kurun.
3. İzin isterse: Ayarlar > Güvenlik > **Bilinmeyen kaynaklar**'a bu dosya yöneticisi için izin verin.

### Yol B — Wi-Fi ile (kablosuz)
1. TV'de: Ayarlar > Cihaz tercihleri > Hakkında > **Derleme (Build)** satırına 7 kez OK basın.
2. Geri gelin: **Geliştirici seçenekleri** > **USB hata ayıklama** (varsa **Ağ hata ayıklama / ADB**) açın.
3. TV'nin IP adresini not edin (Ayarlar > Ağ).
4. Android Studio'da alttaki **Terminal** sekmesine yazın: `adb connect TV_IP_ADRESI:5555`
   (TV'de "izin ver" sorusuna Evet deyin.)
5. Üstte cihaz listesinde TV görünür; yeşil **Run ▶** tuşuna basın, uygulama kurulup açılır.

## 5. İlk kullanım

1. Uygulamayı açın; **Kaynak Ekle** ekranı gelir.
2. **Xtream Kodları**: sunucu (örn. `http://sunucu.com:8080`), kullanıcı adı, şifre.
   **M3U Linki**: `http://...` ile başlayan liste linki.
3. **Kaydet ve Bağlan**. İlk yükleme büyük listelerde 1-2 dakika sürebilir; sonraki açılışlar hızlıdır (liste saklanır, 12 saatte bir kendini yeniler).

## 6. Kumanda kullanımı

| Nerede | Tuş | Ne yapar |
|---|---|---|
| Canlı yayın (tam ekran) | **SAĞ** | Kanal listesi açılır |
| Canlı yayın | **SOL** | Kanal grupları açılır (en üstte ★ Favoriler) |
| Gruplar paneli | **SOL** (tekrar) | Ana menü: Canlı TV · Filmler · Diziler · Ayarlar |
| Gruplar paneli | **SAĞ** veya OK | O grubun kanalları |
| Kanal listesi | OK | Kanalı izle |
| Kanal listesi | **OK'a uzun bas** | Kanalı favoriye ekle / çıkar (★) |
| Gruplar paneli | **OK'a uzun bas** | Grubu **gizle** |
| Canlı yayın | ▲ / ▼ (veya CH+/CH−) | Önceki / sonraki kanal |
| Canlı yayın | OK'a uzun bas | Şu an izlenen kanalı favoriye ekle / çıkar |
| Her yerde | Geri | Bir panel/ekran geri |

**Gizlenen grupları geri açmak:** Ayarlar > *Canlı TV / Film / Dizi gruplarını yönet* > OK ile göster/gizle.

Filmler ve Diziler ekranında: soldaki gruplardan seçin, SAĞ ile listeye geçin, OK ile oynatın. Oynatırken: OK duraklat, ◀▶ 10 sn, ▲▼ 1 dk atlama.

## 7. Sorun giderme

- **Kanal açılmıyor / "Yayın açılamadı":** Ayarlar > *Yayın formatı*'nı `m3u8` ↔ `ts` değiştirin.
- **Liste boş / yüklenemedi:** Link/şifre doğru mu? Ayarlar > *Listeyi yenile*.
- **Filmler/Diziler boş (M3U):** Düz M3U listeler film/dizi ayrımı içermeyebilir; Xtream kodlarıyla tam çalışır.
- **Yazı yazmak zor:** Telefona "Android TV / Google TV" uygulamasını kurun, telefon klavyesiyle yazın.

## Henüz olmayanlar (sonraki sürüm)
Yayın rehberi (EPG), arama, ebeveyn kilidi, kanal numarası ile geçiş.

## Proje yapısı (merak ederseniz)
- `Models.kt` veri tipleri · `M3uParser.kt` M3U okuyucu · `Repository.kt` Xtream API
- `Store.kt` favoriler/gizli gruplar/kaynaklar · `AppViewModel.kt` uygulama mantığı + oynatıcı
- `ui/LiveScreen.kt` kumanda paneli akışı (SAĞ/SOL/uzun OK) · diğer `ui/` dosyaları ekranlar
