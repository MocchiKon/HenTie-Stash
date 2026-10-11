# HenTie — your local comics library

HenTie is a small website that runs **on your own computer** for browsing and managing your
comics collection. You open it in a normal web browser. Other devices on your home network
(phone, tablet, another PC) can open it too.

There is no internet account, no cloud, no installation wizard — just one program file.

---

## 1. Running it

There are two downloads:

| Download | For | Java needed? |
|----------|-----|--------------|
| **`HenTie-windows.zip`** | Windows | **No** — Java comes with it (the `jre` folder) |
| **`HenTie-all-platforms.zip`** | Windows, Linux, macOS | **Yes, Java 21 (or newer)** |

If you need Java, download the free "Temurin 21" installer from <https://adoptium.net/> and run it.

Unzip the download and keep everything in the folder together (`HenTie.exe` needs its `jre` folder
next to it, and both need `bin`). Then, in that folder:

| You are on… | Do this |
|-------------|---------|
| **Windows** | Double-click **`HenTie.exe`** (or **`start.bat`**) |
| Anything else | Run **`start.sh`**, double-click **`HenTie.jar`**, or run `java -jar HenTie.jar` in a terminal |

A black window may appear — **leave it open** while you use the app (closing it stops the app).
Your browser should open automatically at <http://localhost:8080>. If it doesn't, open that
address yourself.

Everything else — logging in, opening the app from your phone, where your images live, backups,
downloading, image compression and setting up ComfyUI — is explained on the **Tutorial** page, linked at
the top of every page of the app.

Upgrading from an older **H2**-based build? See [migration/MIGRATION.md](migration/MIGRATION.md) for the
one-time H2 → SQLite data-migration steps.

---

## 2. Building it yourself (optional, for developers)

```
./mvnw clean package
```

This produces `target/HenTie.jar` (runs anywhere with Java) and, on Windows, `target/HenTie.exe` with
a bundled Java runtime in `target/jre` (the exe needs it beside it, or an installed Java 21+).
`./mvnw package -PpackageForRelease` also builds the release zips.
See [CLAUDE.md](CLAUDE.md) for the architecture and developer notes.
