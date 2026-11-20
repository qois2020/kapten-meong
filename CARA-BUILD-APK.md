# Kapten Meong Sehat – proyek Android

Isi proyek ini: aplikasi Kapten Meong (berat badan, makan, olahraga, langkah, Diet IF)
yang berjalan offline di HP, ditambah hitung langkah otomatis memakai sensor langkah HP.

## Cara 1 – Lewat GitHub (tanpa install apa pun, ±5 menit)

1. Buat akun di https://github.com (gratis) lalu klik **New repository**,
   beri nama `kapten-meong`, pilih **Public** atau **Private**, klik **Create repository**.
2. Di halaman repo baru, klik **uploading an existing file**.
3. Ekstrak file ZIP ini, lalu seret **semua isi folder `KaptenMeong`**
   (termasuk folder `.github`) ke halaman upload. Klik **Commit changes**.
   - Kalau folder `.github` tidak ikut terseret (folder tersembunyi), buat file baru lewat
     **Add file > Create new file**, beri nama `.github/workflows/build-apk.yml`,
     lalu tempel isi file `build-apk.yml` dari ZIP ini.
4. Buka tab **Actions**. Proses "Build APK" berjalan otomatis (±4 menit).
5. Setelah centang hijau, klik proses tersebut, lalu unduh **KaptenMeong-apk** di bagian
   *Artifacts*. Ekstrak, kirim file `.apk` ke HP.

## Cara 2 – Lewat Android Studio di laptop

1. Install Android Studio (https://developer.android.com/studio).
2. **File > Open**, pilih folder `KaptenMeong`. Tunggu proses *Gradle Sync* selesai.
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. Klik **locate** pada notifikasi; file ada di `app/build/outputs/apk/`.

## Memasang di HP

1. Buka file `.apk` di HP. Jika muncul peringatan, izinkan **Instal aplikasi tidak dikenal**
   untuk aplikasi pembuka file (Files/Chrome/WhatsApp).
2. Saat pertama dibuka, izinkan akses **Aktivitas fisik** supaya langkah dihitung otomatis.

## Catatan

- Data tersimpan di HP. Menghapus aplikasi akan menghapus datanya.
- Langkah tetap terhitung oleh sensor walau aplikasi ditutup; angkanya masuk saat aplikasi dibuka.
- Kalau HP tidak punya sensor langkah, pakai timer jalan atau isi langkah manual.
