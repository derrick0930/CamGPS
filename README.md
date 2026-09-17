# CamGPS

| Informasi / Information | Keterangan / Detail |
| --- | --- |
| Nama Aplikasi / App Name | CamGPS |
| Ukuran File / File Size | ~6 MB |
| Iklan / Ads | No Ads / Bebas Iklan |
| Versi / Version | 1.0.0 |
| Bahasa / Language | Kotlin |
| Platform | Android 7.0+ (API 24+) |

---

## English

### Introduction
CamGPS is an Android camera application built using Kotlin and Gradle. When a photo is taken, actual device GPS location information (latitude and longitude coordinates), complete reverse-geocoded address, date and time with GMT timezone, and a Google satellite map thumbnail are automatically stamped onto the bottom of the photo. Coordinates are strictly sourced from the device's hardware GPS sensors and cannot be manually configured. The app also supports Photo, Portrait, and Video recording modes.

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

---

## Bahasa Indonesia

### Pengenalan
CamGPS adalah aplikasi kamera Android yang dikembangkan menggunakan Kotlin dan Gradle. Saat foto diambil, informasi lokasi GPS aktual dari perangkat (koordinat lintang dan bujur), alamat lengkap hasil reverse-geocoding, tanggal dan waktu beserta zona waktu GMT, serta thumbnail peta satelit Google secara otomatis dicetak pada bagian bawah foto. Koordinat diperoleh langsung dari sensor GPS perangkat dan tidak dapat diubah secara manual. Aplikasi ini juga mendukung mode Foto, Portrait, dan Perekaman Video.

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
