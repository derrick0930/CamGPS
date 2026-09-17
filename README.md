# CamGPS 📸📍

**CamGPS** adalah aplikasi kamera Android modern yang dibangun menggunakan **Kotlin** dan **Gradle**. Aplikasi ini secara otomatis membakar stempel (*watermark stamp*) berisi informasi lokasi aktual, koordinat presisi, alamat lengkap, tanggal & waktu, serta thumbnail foto satelit aerial langsung ke bagian bawah foto yang diambil.

Koordinat lokasi diambil secara langsung dari sensor GPS perangkat (**tidak dapat diubah atau dimanipulasi secara manual**) sehingga sangat cocok untuk dokumentasi lapangan, absensi kerja, survei, inspeksi proyek, dan pelaporan kegiatan resmi.

---

## ✨ Fitur Utama

- 📍 **GPS Akurat & Anti-Manipulasi**: Menggunakan Google Play Services `FusedLocationProviderClient` (High Accuracy) dan sensor GPS hardware. Koordinat lintang dan bujur disajikan hingga 6 digit desimal (`Lat ... Long ...`).
- 🛰️ **Thumbnail Peta Satelit Google**: Menampilkan citra satelit/aerial nyata di sudut stempel dengan pin merah 3D yang terpusat tepat pada posisi koordinat pengguna (*pixel-exact centering*).
- 🏷️ **Alamat Lengkap Otomatis**: Fitur *Reverse Geocoding* otomatis mengonversi koordinat menjadi nama jalan, kelurahan/desa, kecamatan, kabupaten/kota, provinsi, kode pos, dan bendera negara.
- 🕒 **Tanggal, Waktu & Zona Waktu**: Format waktu lengkap dengan hari dan offset GMT (contoh: `Jumat, 19/06/2026 10:29 AM GMT +07:00`).
- 📷 **3 Mode Kamera**:
  - **PHOTO**: Pengambilan foto resolusi tinggi dengan stempel kartu transparan modern.
  - **PORTRAIT**: Efek *depth-of-field* / blur latar belakang yang halus di sekitar subjek.
  - **VIDEO**: Perekaman video MP4 lengkap dengan indikator waktu rekam dan penyimpanan otomatis ke galeri.
- 🔄 **Kontrol Kamera Lengkap**: Mendukung ganti kamera (depan/belakang), pengaturan lampu kilat (*Auto*, *On*, *Off*), dan indikator status sinyal GPS (*GPS Locked*).

---

## 🚀 Cara Build & Menjalankan

### Kebutuhan Sistem
- **Java**: JDK 17 (misal: Eclipse Adoptium Temurin 17)
- **Android SDK**: Platform 36 & Build-Tools 35.0.0+

### Langkah Build Singkat

1. **Clone repository:**
   ```bash
   git clone https://github.com/derrick0930/CamGPS.git
   ```

2. **Masuk ke folder proyek:**
   ```bash
   cd CamGPS
   ```

3. **Jalankan perintah build release:**
   - **Linux / macOS:**
     ```bash
     ./gradlew assembleRelease
     ```
   - **Windows (PowerShell / Command Prompt):**
     ```cmd
     gradlew.bat assembleRelease
     ```

### 📦 Lokasi File APK
Setelah proses build selesai, file APK siap instal (*signed*) dapat ditemukan di:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 📱 Cara Instal ke Perangkat

- **Install via ADB:**
  ```bash
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```
- **Install Langsung:** Salin file `app-release.apk` ke ponsel Android Anda lalu tap untuk menginstal.

---

## 🛠️ Lisensi
Dibuat untuk keperluan dokumentasi dan fotografi berbasis lokasi presisi.
