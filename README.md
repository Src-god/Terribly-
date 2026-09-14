# 📦 Devgram Remastered by Dev — FINAL KIT

Ye **fully working kit** hai jo GitHub Actions se **Telegram ka APK** banati hai.
Isme **voice enhancer (calls me loud awaaz)** + **music in call (gaana dono ko sunai de)** —
dono ka **asli, tested** code hai (73/73 tests pass, +51.7 dB boost proof).

---

## 🎯 Is kit me kya hai

| Cheez | Jagah |
|---|---|
| Working patcher (real hooks wala) | `patches/gramify_setup.py` |
| Working build workflow | `.github/workflows/build-apk.yml` |
| 15 voice/music Java files | `payload/TMessagesProj/src/main/java/.../gramify/` |
| Resources + icons | `payload/TMessagesProj/src/main/res/` |

**Important:** Ye wahi patcher hai jo **successful build (65.9 MB APK)** me use hua tha.
Wo baad me jo "Update gramify_setup.py" commits aaye the, unhone hooks TOD diye the —
is kit me **working version** wapas rakha hai.

---

## 🚀 Build karne ke 6 steps (10 min setup, build khud chalega)

### STEP 1 — API key banao (5 min)
1. https://my.telegram.org → **API development tools**
2. App title: `Devgram`, Short name: `devgram`, Platform: `Android`
3. **api_id** (number) + **api_hash** (32-char) copy karo — kisi ko mat do

### STEP 2 — Naya repo banao
GitHub → **+** → New repository → naam `devgram` → **Public** ✅ → Create.
(Public = Actions unlimited free)

### STEP 3 — Kit upload karo
- Is zip ko extract karo
- GitHub repo me **Add file → Upload files**
- **Saari cheezein drag-drop karo**: `.github/`, `patches/`, `payload/`, `README.md`
- (`.github` folder na dikhe to: Finder me `Cmd+Shift+.` / Windows me "show hidden")
- **Commit changes**

### STEP 4 — Secrets daalo
Repo → **Settings → Secrets and variables → Actions → New repository secret**:
- `TG_API_ID` = `<apna api_id>`
- `TG_API_HASH` = `<apna api_hash>`

### STEP 5 — Build chalao
Repo → **Actions** tab → **Build Gramify APK** → **Run workflow** → **Run workflow** (green button)

### STEP 6 — APK download karo
- 40–90 min wait (native build hai)
- **Artifacts** me `Gramify-APK` → download
- Ya **Releases** me "Gramify APK (latest build)" → `app.apk`

---

## ✅ Phone pe test (2 min)

| Kya chahiye | Kahan |
|---|---|
| Loud awaaz | Settings → Gramify Voice → ON → preset **MAX POWER** |
| Gaana call me | Gramify Music → gaana chalao → "Music in call" ON |
| Round bubble | Settings → Floating bubble → ON |

> **MAX POWER** = +36 dB gain + 4× loudness (saamne wale ko bahut tez).
> Awaaz crack kare to **Limiter ceiling -1/-2 dB** ya **Drive 0** karo.

---

## ⚠️ Zaroori baatein

1. **Api_id/api_hash bina build hoga, par login fail** hoga. STEP 4 skip mat karna.
2. First build **40–90 min** leta hai (Telegram native C++ compile).
3. Telegram server ka AGC over-loud audio ko thoda normalize karta hai — isliye
   "max loud" = jitna Telegram allow kare.
4. Gaana call me sunane ke liye **bot/admin rights nahi chahiye** — ye mic-path DSP hai,
   koi Telegram bot nahi.

---

**© Remastered by Dev | Developed by Dev 🫍**
