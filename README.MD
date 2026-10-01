# Kitsune Mask 31.0

Bu depo, Kitsune Magisk çizgisinin resmi olmayan devamıdır. Resmi Magisk değildir. Çekirdek ortaktır; yönetici uygulamasının iki hali vardır.

Paket adı `io.github.huskydg.magisk`, sürüm kodu `31000`. Classic ve Stardust aynı anda kurulamaz. Biri diğerinin yerini alır. Release ve debug için de geçerlidir: kurulan paket öncekinin üzerine yazar.

Güncel dosyalar: [v31.0-kitsune-31000](https://github.com/EfeAtalay/KitsuneMask31000/releases/tag/v31.0-kitsune-31000)

| Dosya | Hali | Sürüm adı |
| --- | --- | --- |
| `Kitsune.Magisk.classic.release.v31.0-kitsune-31000.apk` | Classic, release | `31.0-kitsune` |
| `Kitsune.Magisk.stardust.release.v31.0-kitsune-stardust-31000.apk` | Stardust, release | `31.0-kitsune-stardust` |
| `Kitsune.Magisk.classic.debug.v31.0-kitsune-31000.apk` | Classic, debug | `31.0-kitsune` |
| `Kitsune.Magisk.stardust.debug.v31.0-kitsune-stardust-31000.apk` | Stardust, debug | `31.0-kitsune-stardust` |

Günlük kullanım için release paketleri yeter. Debug paketleri hata ayıklama açıktır.

Cihaz zaten köklüyse uygulamadan Direct Install ile kurup yeniden başlatın. İki arayüz aynı Magisk çekirdeğini taşır. Fark, yöneticinin ekranlarındadır.

## Classic

Classic, Kitsune’nin alışılmış yönetici arayüzüdür. Ana sayfa, modüller, süper kullanıcı listesi ve ayarlar bu düzendedir. Tema paleti, yan ray ve Stardust’a özel düğmeler bu pakette yoktur.

Sürüm adı `31.0-kitsune`. Modül kurma, kök politikası ve Magisk kurulumu Classic ekranlardan yapılır. Çekirdek kurulumundan sonra DenyList ve SuList kuralları cihazda geçerlidir; bu kuralların yeni düğmeleri Stardust ekranındadır.

## Stardust

Stardust, aynı çekirdeğin üzerine kurulan Material 3 arayüzüdür. Sürüm adı `31.0-kitsune-stardust`.

- Gezinme yan raydadır.
- Tema paleti ve Material You seçilebilir. AMOLED anahtarı koyu zemini siyaha çeker. Palet seçince temalar ekranından çıkılır; AMOLED değişince o ekranda kalınır.
- Ana sayfa sürüm satırını ve kurulumu ayrı gösterir. Kaldır, kurulumun yanında durur.
- Günlük ekranı iki hap kullanır: süper kullanıcı günlüğü ve Magisk günlüğü.
- Modül kartlarında eylem düğmesi durur. Altta tek kurulum düğmesi vardır.
- Modül deposu Googlers, alternatif depo ve resmi Magisk kanallarını birlikte okur. Yalnız KernelSU veya APatch diye işaretlenmiş kayıtlar listeye girmez.

### Süper kullanıcı

Stardust süper kullanıcı ekranı DenyList ve SuList haplarını, Grant ve Uygulama gizle düğmelerini gösterir.

- Hap, çalışan moda bakar. Mod değişimi yeniden başlatmadan sonra uygulanır ve geçmeden önce sorulur. Çalışmayan listenin tablosu boşaltılır.
- Grant, çalışan moda yazar. SuList’te seçilen uygulama SuList’e eklenir ve kökü kalır. DenyList’te hem kök kaydı hem DenyList kaydı yazılır.
- DenyList’teki uygulama ve SuList’te olmayan uygulama `su` alamaz.
- Kök kısıtlıyken hassas derleme özellikleri maskelenir.
- Uygulama gizle, listedeki satırdan bağımsız uygulama seçtirir. DenyList’te seçim hidelist’e yazılır ve kalır. SuList, kökü açık tutan listedir; gizlenen uygulama oraya eklenmez, listeden çıkar. Satırda “SuList’te değil, kök yok” görünür.
- Sonuç, kapanana kadar pencerede kalır. Gizlenen uygulama Kök izinleri listesine iner. Bu satırda kök anahtarı yoktur. Kök verilmiş uygulamaların anahtarı durur.
- Şimdi gizle, çalışan sürecin Magisk bağlarını söker. Gizlemeyi test et, o süreçteki Magisk izini sayar.

Yerleşik Zygisk kapalıyken zygote, ReZygisk ve Zygisk Next’e bırakılır.

## Lisans

Kod [GPL-3.0-or-later](LICENSE) altındadır. Ağustos 2025’te özgün Kitsune Magisk bakımcısı kendi deposunu arşivlemiştir. Bu depo o duyurunun yerine geçmez; aynı lisansla sürdürülen ayrı bir ağaçtır.
