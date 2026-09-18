# CamGPS

| Informasi / Information | Keterangan / Detail |
| --- | --- |
| Nama Aplikasi / App Name | CamGPS |
| Ukuran File / File Size | 6 MB |
| Iklan / Ads | No Ads / Bebas Iklan |
| Bahasa / Language | Kotlin |
| Platform | Android 7.0+ (API 24+) |

---

## English

### Introduction
CamGPS is an Android camera application built using Kotlin and Gradle. When a photo is taken, actual device GPS location information (latitude and longitude coordinates), complete reverse-geocoded address, date and time with GMT timezone, and a Google satellite map thumbnail are automatically stamped onto the bottom of the photo. Coordinates are strictly sourced from the device's hardware GPS sensors and cannot be manually configured. The app supports Live Photo, Photo, and Video recording modes.

### Key Features
- **Real-Time GPS Stamp**: Automatically stamps exact GPS coordinates, reverse-geocoded address, timestamp (GMT), and Google satellite map thumbnail onto photos.
- **Hardware GPS Integrity**: Coordinates are strictly pulled from hardware sensors to ensure authenticity.
- **Camera Modes**:
  - **Live Photo (Beta)**: Captures a high-resolution stamped photo alongside a short motion clip that can be previewed interactively with live playback.
  - **Photo**: Instant capture with permanent GPS watermark card overlay.
  - **Video**: High-definition video recording.

> [!WARNING]
> **Live Photo is currently in Beta**:
> The Live Photo feature is an experimental feature and is still under active development. You may encounter bugs, stability issues, or capture inconsistencies on certain Android devices and camera sensors. Bug reports, logs, and feedback are very welcome!

### How to Build

1. Clone the repository:
   ```bash
   git clone https://github.com/derrick0930/CamGPS.git
   ```

2. Navigate to the project directory:
   ```bash
   cd CamGPS
   ```

3. Build the release APK:
   - Linux / macOS:
     ```bash
     ./gradlew assembleRelease
     ```
   - Windows:
     ```cmd
     gradlew.bat assembleRelease
     ```

The output APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

### Contributing
Contributions, bug reports, and feature suggestions are warmly welcomed! Help make CamGPS better:

1. **Fork** the repository.
2. **Create a branch** for your feature or bug fix:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Commit** your changes with meaningful commit messages:
   ```bash
   git commit -m "Add: descriptive summary of change"
   ```
4. **Push** your branch:
   ```bash
   git push origin feature/your-feature-name
   ```
5. **Open a Pull Request** describing your changes.

If you discover any bugs (especially in the Live Photo feature) or have ideas for enhancements, feel free to open an issue in the [GitHub Issues](https://github.com/derrick0930/CamGPS/issues) section.

---

## Bahasa Indonesia

### Pengenalan
CamGPS adalah aplikasi kamera Android yang dikembangkan menggunakan Kotlin dan Gradle. Saat foto diambil, informasi lokasi GPS aktual dari perangkat (koordinat lintang dan bujur), alamat lengkap hasil reverse-geocoding, tanggal dan waktu beserta zona waktu GMT, serta thumbnail peta satelit Google secara otomatis dicetak pada bagian bawah foto. Koordinat diperoleh langsung dari sensor GPS perangkat dan tidak dapat diubah secara manual. Aplikasi ini mendukung mode Foto Live, Foto, dan Perekaman Video.

### Fitur Utama
- **Cap GPS Real-Time**: Otomatis mencetak koordinat presisi, alamat hasil reverse-geocoding, tanggal/waktu (GMT), serta cuplikan peta satelit Google ke foto.
- **Integritas GPS Hardware**: Koordinat diambil langsung dari sensor GPS fisik perangkat sehingga akurat dan tidak dapat dimanipulasi secara manual.
- **Mode Kamera**:
  - **Foto Live (Beta)**: Mengabadikan foto beresolusi tinggi sekaligus klip video gerakan singkat (*live motion*) yang dapat diputar secara interaktif pada menu pratinjau.
  - **Foto**: Pengambilan foto instan dengan watermark informasi GPS permanen.
  - **Video**: Perekaman video resolusi tinggi langsung dari aplikasi.

> [!WARNING]
> **Fitur Foto Live Masih Tahap Beta**:
> Fitur Foto Live (Live Photo) saat ini masih berstatus **Beta** dan dalam tahap pengembangan. Kemungkinan masih terdapat bug, kendala kestabilan, atau kompatibilitas pada perangkat/sensor kamera tertentu. Laporan kendala dan masukan sangat kami harapkan!

### Cara Build

1. Clone repository:
   ```bash
   git clone https://github.com/derrick0930/CamGPS.git
   ```

2. Masuk ke direktori proyek:
   ```bash
   cd CamGPS
   ```

3. Jalankan perintah build release:
   - Linux / macOS:
     ```bash
     ./gradlew assembleRelease
     ```
   - Windows:
     ```cmd
     gradlew.bat assembleRelease
     ```

File APK hasil build berada di:
`app/build/outputs/apk/release/app-release.apk`

### Kontribusi
Kami sangat menyambut kontribusi dari komunitas open-source! Bantuan Anda dalam memperbaiki bug, menyempurnakan fitur Foto Live, ataupun menambahkan kemampuan baru akan sangat bermanfaat:

1. **Fork** repositori ini ke akun GitHub Anda.
2. **Buat branch baru** untuk fitur atau perbaikan Anda:
   ```bash
   git checkout -b fitur/nama-fitur
   ```
3. **Commit** perubahan Anda dengan pesan commit yang jelas:
   ```bash
   git commit -m "Menambahkan fitur X atau memperbaiki bug Y"
   ```
4. **Push** branch ke repositori fork Anda:
   ```bash
   git push origin fitur/nama-fitur
   ```
5. **Kirim Pull Request (PR)** dengan penjelasan mengenai perubahan yang dilakukan.

Jika Anda menemukan bug atau memiliki saran dan ide baru, jangan ragu untuk membuat tiket di [GitHub Issues](https://github.com/derrick0930/CamGPS/issues). Setiap kontribusi sangat kami hargai!
